package com.somphie.shared.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import com.jayway.jsonpath.JsonPath
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

/** Comprueba arranque HTTP, metadatos, reloj y conectividad a PostgreSQL real. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class BackendSmokeTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var clock: Clock

    /** Verifica que la versión publicada coincida con los metadatos del build y el contrato. */
    @Test
    fun `version endpoint returns build and contract metadata`() {
        mockMvc.perform(get("/version"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.build").value("0.0.1-SNAPSHOT"))
            .andExpect(jsonPath("$.contractVersion").value("0.3.0"))
    }

    /** Confirma que Actuator exponga salud sin detalles internos en el perfil de pruebas. */
    @Test
    fun `health endpoint is available without infrastructure details`() {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
    }

    /** Prueba que la aplicación use la base PostgreSQL del contenedor en vez de una base simulada. */
    @Test
    fun `postgres container is reachable by the application`() {
        assertEquals(1, jdbcTemplate.queryForObject("SELECT 1", Int::class.java))
    }

    /** Fija UTC como zona del reloj compartido para evitar diferencias entre máquinas. */
    @Test
    fun `application clock uses UTC`() {
        assertEquals(ZoneOffset.UTC, clock.zone)
    }

    /** Comprueba en una base vacía que Flyway cree el esquema y aplique las semillas base una sola vez. */
    @Test
    fun `foundation migration creates required schema and seeds`() {
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success",
            Int::class.java,
        ))
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '8' AND success",
            Int::class.java,
        ))
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '9' AND success",
            Int::class.java,
        ))
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Int::class.java))
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM refresh_token", Int::class.java))
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM restaurant", Int::class.java))
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM restaurant_settings", Int::class.java))
        assertEquals(4, jdbcTemplate.queryForObject("SELECT count(*) FROM role", Int::class.java))
        assertEquals(39, jdbcTemplate.queryForObject("SELECT count(*) FROM permission", Int::class.java))
        assertEquals(70, jdbcTemplate.queryForObject("SELECT count(*) FROM role_permission", Int::class.java))
        assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM station", Int::class.java))
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT count(*) FROM role_permission WHERE role_code = 'KITCHEN' AND permission_code = 'item.prepare'",
            Int::class.java,
        ))
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT count(*) FROM role_permission WHERE role_code = 'KITCHEN' AND permission_code = 'settings.manage'",
            Int::class.java,
        ))
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT count(*) FROM role_permission WHERE role_code = 'OWNER' AND permission_code = 'role.manage'",
            Int::class.java,
        ))
        assertEquals(15, jdbcTemplate.queryForObject(
            "SELECT max_staff_discount_percent FROM restaurant_settings",
            Int::class.java,
        ))
        assertEquals(100, jdbcTemplate.queryForObject(
            "SELECT max_tip_percent FROM restaurant_settings",
            Int::class.java,
        ))
    }

    @Test
    fun `menu localizes products options and serves a matching etag without a body`() {
        val menu = mockMvc.perform(get("/menu").param("lang", "en"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.locale").value("en"))
            .andExpect(jsonPath("$.currency").value("ARS"))
            .andExpect(jsonPath("$.categories[0].products[0].name").value("Flat white"))
            .andReturn()
        val etag = menu.response.getHeader(HttpHeaders.ETAG)!!
        assertTrue(menu.response.getHeader(HttpHeaders.VARY)!!.contains(HttpHeaders.ACCEPT_LANGUAGE))

        mockMvc.perform(get("/menu").param("lang", "en").header(HttpHeaders.IF_NONE_MATCH, etag))
            .andExpect(status().isNotModified)
            .andExpect(content().string(""))

        mockMvc.perform(
            get("/products/9a2b4c6d-8e0f-4a1b-b3c5-d7e9f1a3b5c7").param("lang", "en"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.optionGroups[0].name").value("Size"))
            .andExpect(jsonPath("$.optionGroups[0].options[1].name").value("Large"))
            .andExpect(jsonPath("$.optionGroups[0].options[1].priceDeltaMinor").value(80000))
    }

    @Test
    fun `catalog reports missing restaurant and malformed or unknown products as not found`() {
        mockMvc.perform(get("/menu").param("restaurantId", "00000000-0000-0000-0000-000000000000"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        mockMvc.perform(get("/products/not-a-uuid"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        mockMvc.perform(get("/products/00000000-0000-0000-0000-000000000000"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `opening and joining a table session persists only a hashed guest token`() {
        val tableCode = createTable()
        mockMvc.perform(get("/public/tables/$tableCode"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hasActiveSession").value(false))
            .andExpect(jsonPath("$.canOpen").value(true))

        val opened = mockMvc.perform(
            post("/public/tables/$tableCode/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":"Host"}"""),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.participant.role").value("HOST"))
            .andReturn().response.contentAsString
        val token = JsonPath.read<String>(opened, "$.guestToken")
        val participantId = UUID.fromString(JsonPath.read<String>(opened, "$.participant.id"))
        val tokenHash = jdbcTemplate.queryForObject(
            "SELECT token_hash FROM session_participant WHERE id = ?",
            String::class.java,
            participantId,
        )!!
        val expectedHash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
        )
        assertTrue(token.startsWith("gt_"))
        assertEquals(expectedHash, tokenHash)
        assertNotEquals(token, tokenHash)

        val joinCode = JsonPath.read<String>(opened, "$.joinCode")
        mockMvc.perform(
            post("/public/tables/$tableCode/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":"Member","joinCode":"$joinCode"}"""),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.participant.role").value("MEMBER"))

        mockMvc.perform(
            post("/public/tables/$tableCode/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":"Intruder","joinCode":"AAAAAA"}"""),
        ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))

        mockMvc.perform(
            post("/public/tables/$tableCode/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":"Second host"}"""),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SESSION_FULL"))
    }

    @Test
    fun `creating an order validates acknowledged total persists audit and replays idempotently`() {
        val tableCode = createTable()
        val opened = mockMvc.perform(
            post("/public/tables/$tableCode/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":"Order guest"}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val token = JsonPath.read<String>(opened, "$.guestToken")
        val sessionId = UUID.fromString(JsonPath.read<String>(opened, "$.session.id"))
        val key = UUID.randomUUID().toString()
        val request = """{"items":[{"productId":"9a2b4c6d-8e0f-4a1b-b3c5-d7e9f1a3b5c7","quantity":1,"optionIds":["e5f6a7b8-c9d0-4e1f-9a2b-3c4d5e6f7081","f6a7b8c9-d0e1-4f2a-8b3c-4d5e6f708192"]}],"acknowledgedTotalMinor":625000}"""

        val created = mockMvc.perform(
            post("/sessions/current/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.totalMinor").value(625000))
            .andReturn()
        val orderId = UUID.fromString(JsonPath.read<String>(created.response.contentAsString, "$.id"))

        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT count(*) FROM customer_order WHERE id = ? AND session_id = ? AND total_minor = 625000",
            Int::class.java,
            orderId,
            sessionId,
        ))
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_event WHERE target_type = 'ORDER' AND target_id = ? AND action = 'ORDER_CREATED'",
            Int::class.java,
            orderId,
        ))

        mockMvc.perform(
            post("/sessions/current/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request),
        ).andExpect(status().isCreated)
            .andExpect(content().json(created.response.contentAsString))
            .andExpect { result -> assertEquals("true", result.response.getHeader("Idempotent-Replayed")) }

        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT count(*) FROM customer_order WHERE session_id = ?",
            Int::class.java,
            sessionId,
        ))

        mockMvc.perform(
            post("/sessions/current/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request.replace("625000", "624999")),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("PRICE_CHANGED"))

        mockMvc.perform(
            post("/sessions/current/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"items":[],"acknowledgedTotalMinor":0}"""),
        ).andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("ORDER_EMPTY"))
    }

    private fun createTable(): String {
        val id = UUID.randomUUID()
        val code = "COV-${UUID.randomUUID()}"
        jdbcTemplate.update(
            "INSERT INTO dining_table (id, restaurant_id, label, table_code) VALUES (?, ?, ?, ?)",
            id,
            UUID.fromString("6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68"),
            "Coverage $code",
            code,
        )
        return code
    }

    private companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16.15-alpine")

        /** Inyecta la conexión temporal antes de crear el contexto Spring. */
        @DynamicPropertySource
        @JvmStatic
        fun configurePostgres(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
