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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
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
    fun `staff endpoints require an authenticated account`() {
        mockMvc.perform(get("/staff/orders"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
    }

    @Test
    fun `staff order authorization checks permission and restaurant scope`() {
        val fixture = createGuestSession()
        val orderId = createOrder(fixture)
        val ownToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")

        mockMvc.perform(
            get("/staff/orders/$orderId").header("Authorization", "Bearer $ownToken"),
        ).andExpect(status().isOk)

        jdbc.update(
            "INSERT INTO restaurant (id, name, timezone, currency) VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
            OTHER_RESTAURANT_ID,
            "Other restaurant",
            "America/Argentina/Buenos_Aires",
            "ARS",
        )
        jdbc.update("DELETE FROM app_user WHERE id = ?", OTHER_USER_ID)
        jdbc.update(
            """
            INSERT INTO app_user (id, restaurant_id, email, password_hash, display_name, status)
            VALUES (?, ?, ?, ?, ?, 'ACTIVE')
            """.trimIndent(),
            OTHER_USER_ID,
            OTHER_RESTAURANT_ID,
            OTHER_EMAIL,
            passwordEncoder.encode(OLD_PASSWORD),
            "Other Waiter",
        )
        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'WAITER')", OTHER_USER_ID)
        val otherToken = json(
            mockMvc.perform(loginRequest(OTHER_EMAIL, OLD_PASSWORD))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString,
            "$.accessToken",
        )

        mockMvc.perform(
            get("/staff/orders/$orderId").header("Authorization", "Bearer $otherToken"),
        ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        mockMvc.perform(
            get("/staff/orders")
                .param("sessionId", fixture.sessionId)
                .header("Authorization", "Bearer $otherToken"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$").isEmpty)

        jdbc.update("DELETE FROM user_role WHERE user_id = ?", USER_ID)
        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'KITCHEN')", USER_ID)
        val stationId = addOrderItem(orderId, fixture)
        val kitchenToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        mockMvc.perform(
            get("/staff/orders")
                .param("sessionId", fixture.sessionId)
                .header("Authorization", "Bearer $kitchenToken"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$").isEmpty)

        jdbc.update("INSERT INTO user_station (user_id, station_id) VALUES (?, ?)", USER_ID, stationId)
        mockMvc.perform(
            get("/staff/orders")
                .param("sessionId", fixture.sessionId)
                .header("Authorization", "Bearer $kitchenToken"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$").isEmpty)

        jdbc.update("UPDATE customer_order SET status = 'ACCEPTED' WHERE id = ?", UUID.fromString(orderId))
        jdbc.update("UPDATE order_item SET status = 'IN_PREPARATION' WHERE order_id = ?", UUID.fromString(orderId))
        mockMvc.perform(
            get("/staff/orders")
                .param("sessionId", fixture.sessionId)
                .header("Authorization", "Bearer $kitchenToken"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(orderId))

        mockMvc.perform(
            post("/staff/orders/$orderId/accept")
                .header("Authorization", "Bearer $kitchenToken")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `authorized waiter accepts an order and idempotently replays the persisted decision`() {
        val fixture = createGuestSession()
        val orderId = createOrder(fixture)
        addOrderItem(orderId, fixture)
        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val key = UUID.randomUUID().toString()
        val request = """{"excludedItems":[]}"""

        val accepted = mockMvc.perform(
            post("/staff/orders/$orderId/accept")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andReturn()

        assertEquals(
            "ACCEPTED",
            jdbc.queryForObject("SELECT status FROM customer_order WHERE id = ?", String::class.java, UUID.fromString(orderId)),
        )
        assertEquals(
            "QUEUED",
            jdbc.queryForObject("SELECT status FROM order_item WHERE order_id = ?", String::class.java, UUID.fromString(orderId)),
        )

        mockMvc.perform(
            post("/staff/orders/$orderId/accept")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request),
        ).andExpect(status().isOk)
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andExpect(content().json(accepted.response.contentAsString))
    }

    @Test
    fun `authorized waiter rejection cancels pending items and maps unknown reason safely`() {
        val fixture = createGuestSession()
        val orderId = createOrder(fixture)
        addOrderItem(orderId, fixture)
        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")

        mockMvc.perform(
            post("/staff/orders/$orderId/reject")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reasonCode":"NOT_A_REASON","reasonText":"Unavailable"}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REJECTED"))

        assertEquals(
            "REJECTED",
            jdbc.queryForObject("SELECT status FROM customer_order WHERE id = ?", String::class.java, UUID.fromString(orderId)),
        )
        assertEquals(
            "CANCELLED",
            jdbc.queryForObject("SELECT status FROM order_item WHERE order_id = ?", String::class.java, UUID.fromString(orderId)),
        )
        assertEquals(
            "OTHER",
            jdbc.queryForObject("SELECT rejection_reason FROM customer_order WHERE id = ?", String::class.java, UUID.fromString(orderId)),
        )
    }

    @Test
    fun `guest order access requires a valid token scoped to its session`() {
        val owner = createGuestSession()
        val otherParticipant = createGuestSession()
        val orderId = createOrder(owner)

        mockMvc.perform(
            get("/public/tables/${owner.tableCode}")
                .header("Authorization", "Bearer ${owner.token}"),
        ).andExpect(status().isOk)

        mockMvc.perform(get("/sessions/current/orders"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))

        mockMvc.perform(
            get("/sessions/current/orders").header("Authorization", "Bearer gt_invalid"),
        ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))

        mockMvc.perform(
            get("/orders/$orderId").header("Authorization", "Bearer ${owner.token}"),
        ).andExpect(status().isOk)

        mockMvc.perform(
            get("/orders/$orderId").header("Authorization", "Bearer ${otherParticipant.token}"),
        ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        val memberToken = createMember(owner)
        mockMvc.perform(
            post("/orders/$orderId/cancel")
                .header("Authorization", "Bearer $memberToken"),
        ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))

        mockMvc.perform(
            post("/orders/$orderId/cancel")
                .header("Authorization", "Bearer ${owner.token}"),
        ).andExpect(status().isOk)

        jdbc.update(
            "UPDATE session_participant SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?",
            UUID.fromString(owner.participantId),
        )
        mockMvc.perform(
            get("/orders/$orderId").header("Authorization", "Bearer ${owner.token}"),
        ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("SESSION_ACCESS_REVOKED"))
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

    private fun login(password: String) = mockMvc.perform(loginRequest(EMAIL, password))
        .andExpect(status().isOk)
        .andReturn()

    private fun loginRequest(email: String, password: String) = post("/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"email":"$email","password":"$password"}""")

    private fun loginRequest(password: String) = loginRequest(EMAIL, password)

    private fun createGuestSession(): GuestFixture {
        val tableId = UUID.randomUUID()
        val tableCode = "SEC-${UUID.randomUUID()}"
        jdbc.update(
            "INSERT INTO dining_table (id, restaurant_id, label, table_code) VALUES (?, ?, ?, ?)",
            tableId,
            RESTAURANT_ID,
            "Security $tableCode",
            tableCode,
        )
        val result = mockMvc.perform(
            post("/public/tables/$tableCode/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":"Test guest"}"""),
        ).andExpect(status().isCreated)
            .andReturn().response.contentAsString
        return GuestFixture(
            sessionId = json(result, "$.session.id"),
            participantId = json(result, "$.participant.id"),
            token = json(result, "$.guestToken"),
            tableCode = tableCode,
        )
    }

    private fun createOrder(fixture: GuestFixture): String {
        val orderId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO customer_order
                (id, restaurant_id, session_id, participant_id, status, display_code, summary, total_minor, created_at)
            VALUES (?, ?, ?, ?, 'PENDING_REVIEW', '#SEC', 'Security test', 100, CURRENT_TIMESTAMP)
            """.trimIndent(),
            orderId,
            RESTAURANT_ID,
            UUID.fromString(fixture.sessionId),
            UUID.fromString(fixture.participantId),
        )
        return orderId.toString()
    }

    private fun createMember(fixture: GuestFixture): String {
        val token = "gt_${UUID.randomUUID()}"
        val tokenHash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
        )
        jdbc.update(
            """
            INSERT INTO session_participant (id, session_id, display_name, role, token_hash)
            VALUES (?, ?, 'Test member', 'MEMBER', ?)
            """.trimIndent(),
            UUID.randomUUID(),
            UUID.fromString(fixture.sessionId),
            tokenHash,
        )
        return token
    }

    private fun addOrderItem(orderId: String, fixture: GuestFixture): UUID {
        val product = jdbc.query(
            "SELECT id, station_id FROM product ORDER BY id LIMIT 1",
        ) { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getObject("station_id", UUID::class.java) }
            .first()
        jdbc.update(
            """
            INSERT INTO order_item
                (id, order_id, session_id, product_id, product_name, station_id, quantity,
                 base_price_minor, unit_price_minor, line_total_minor, status)
            VALUES (?, ?, ?, ?, 'Security test item', ?, 1, 100, 100, 100, 'PENDING')
            """.trimIndent(),
            UUID.randomUUID(),
            UUID.fromString(orderId),
            UUID.fromString(fixture.sessionId),
            product.first,
            product.second,
        )
        return product.second
    }

    private data class GuestFixture(
        val sessionId: String,
        val participantId: String,
        val token: String,
        val tableCode: String,
    )

    private fun json(body: String, path: String): String = JsonPath.read(body, path)

    private companion object {
        const val EMAIL = "identity-test@somphie.demo"
        const val OLD_PASSWORD = "old-password-123"
        const val NEW_PASSWORD = "new-password-456"
        const val OTHER_EMAIL = "identity-other@somphie.demo"
        val USER_ID = UUID.fromString("e6b8be16-51c5-4835-99f1-10d2259ee099")
        val OTHER_USER_ID = UUID.fromString("99bdfa60-0c8d-4e4a-b5d2-d605098b7277")
        val OTHER_RESTAURANT_ID = UUID.fromString("808a73aa-2d94-42a7-8662-5b874f29cc7e")
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
