package com.somphie.identity.api

import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.hamcrest.Matchers.hasItem
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class IdentityIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @BeforeEach
    fun seedAccount() {
        jdbc.update("DELETE FROM auth_login_attempt")
        jdbc.update("DELETE FROM audit_event WHERE target_id = ?", USER_ID)
        jdbc.update("DELETE FROM refresh_token WHERE user_id = ?", USER_ID)
        jdbc.update("DELETE FROM user_role WHERE user_id = ?", USER_ID)
        jdbc.update("DELETE FROM app_user WHERE id = ?", USER_ID)
        jdbc.update(
            """
            INSERT INTO app_user (id, restaurant_id, email, password_hash, display_name, status)
            VALUES (?, ?, ?, ?, ?, 'ACTIVE')
            """.trimIndent(),
            USER_ID,
            RESTAURANT_ID,
            EMAIL,
            passwordEncoder.encode(OLD_PASSWORD),
            "Test Waiter",
        )
        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'WAITER')", USER_ID)
    }

    @Test
    fun `identity endpoints require a valid staff access token`() {
        mockMvc.perform(get("/me"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.traceId").isNotEmpty)
    }

    @Test
    fun `login me refresh rotation and reuse revocation follow the contract`() {
        val login = login(OLD_PASSWORD)
        val accessToken = json(login.response.contentAsString, "$.accessToken")
        val refreshToken = json(login.response.contentAsString, "$.refreshToken")
        val refreshHash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(refreshToken.toByteArray(Charsets.UTF_8)),
        )
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM refresh_token WHERE token_hash = ?",
            Int::class.java,
            refreshHash,
        ))
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM refresh_token WHERE token_hash = ?",
            Int::class.java,
            refreshToken,
        ))

        mockMvc.perform(get("/me").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value(EMAIL))
            .andExpect(jsonPath("$.roles[0]").value("WAITER"))
            .andExpect(jsonPath("$.permissions").value(hasItem("order.accept")))

        val rotated = mockMvc.perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$refreshToken"}"""),
        ).andExpect(status().isOk)
            .andReturn()
        val nextRefreshToken = json(rotated.response.contentAsString, "$.refreshToken")

        mockMvc.perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$refreshToken"}"""),
        ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"))

        mockMvc.perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$nextRefreshToken"}"""),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `concurrent refresh retries rotate once and revoke the whole family`() {
        val initialRefreshToken = json(login(OLD_PASSWORD).response.contentAsString, "$.refreshToken")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = (1..2).map {
                executor.submit<Int> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    mockMvc.perform(
                        post("/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"refreshToken":"$initialRefreshToken"}"""),
                    ).andReturn().response.status
                }
            }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val statuses = attempts.map { it.get(15, TimeUnit.SECONDS) }.sorted()
            assertEquals(listOf(200, 401), statuses)
            assertEquals(
                2,
                jdbc.queryForObject(
                    "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NOT NULL",
                    Int::class.java,
                    USER_ID,
                ),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `fifth failed login locks account and audits without exposing the email`() {
        repeat(4) {
            mockMvc.perform(loginRequest("wrong-password"))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
        }
        mockMvc.perform(loginRequest("wrong-password"))
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("ACCOUNT_TEMPORARILY_LOCKED"))
            .andExpect(header().exists("Retry-After"))

        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE action = 'identity.login_failed_locked' AND target_id = ?",
            Int::class.java,
            USER_ID,
        ))
    }

    @Test
    fun `login hides whether an email exists and short new passwords use catalog error`() {
        val invalidKnown = mockMvc.perform(loginRequest("wrong-password"))
            .andReturn().response
        val invalidUnknown = mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"missing@somphie.demo","password":"wrong-password"}"""),
        ).andReturn().response
        assertEquals(401, invalidKnown.status)
        assertEquals(401, invalidUnknown.status)
        assertEquals(
            json(invalidKnown.contentAsString, "$.code"),
            json(invalidUnknown.contentAsString, "$.code"),
        )

        val accessToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        mockMvc.perform(
            post("/auth/change-password")
                .header("Authorization", "Bearer $accessToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"$OLD_PASSWORD","newPassword":"short"}"""),
        ).andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.code").value("PASSWORD_TOO_WEAK"))
    }

    @Test
    fun `changing password invalidates access and refresh tokens`() {
        val login = login(OLD_PASSWORD)
        val accessToken = json(login.response.contentAsString, "$.accessToken")
        val refreshToken = json(login.response.contentAsString, "$.refreshToken")

        mockMvc.perform(
            post("/auth/change-password")
                .header("Authorization", "Bearer $accessToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"$OLD_PASSWORD","newPassword":"$NEW_PASSWORD"}"""),
        ).andExpect(status().isNoContent)

        mockMvc.perform(get("/me").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))

        mockMvc.perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$refreshToken"}"""),
        ).andExpect(status().isUnauthorized)

        login(NEW_PASSWORD)
        assertFalse(passwordEncoder.matches(OLD_PASSWORD, jdbc.queryForObject(
            "SELECT password_hash FROM app_user WHERE id = ?",
            String::class.java,
            USER_ID,
        )))
    }

    @Test
    fun `temporary password restricts staff endpoints until it is changed`() {
        jdbc.update("UPDATE app_user SET must_change_password = TRUE WHERE id = ?", USER_ID)
        val accessToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")

        mockMvc.perform(get("/me").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"))

        mockMvc.perform(
            post("/auth/change-password")
                .header("Authorization", "Bearer $accessToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"$OLD_PASSWORD","newPassword":"$NEW_PASSWORD"}"""),
        ).andExpect(status().isNoContent)
    }

    private fun login(password: String) = mockMvc.perform(loginRequest(password))
        .andExpect(status().isOk)
        .andReturn()

    private fun loginRequest(password: String) = post("/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"email":"$EMAIL","password":"$password"}""")

    private fun json(body: String, path: String): String = JsonPath.read(body, path)

    private companion object {
        const val EMAIL = "identity-test@somphie.demo"
        const val OLD_PASSWORD = "old-password-123"
        const val NEW_PASSWORD = "new-password-456"
        val USER_ID = UUID.fromString("e6b8be16-51c5-4835-99f1-10d2259ee099")
        val RESTAURANT_ID = UUID.fromString("6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68")

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16.15-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun configurePostgres(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
