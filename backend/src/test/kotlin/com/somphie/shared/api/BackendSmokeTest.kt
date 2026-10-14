package com.somphie.shared.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
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
import com.somphie.floor.api.ExpireEmptySessionsJob
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Comprueba arranque HTTP, metadatos, reloj y conectividad a PostgreSQL real. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class BackendSmokeTest {
    private val createdRestaurantIds = mutableListOf<UUID>()

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var clock: Clock

    @Autowired
    private lateinit var expireEmptySessionsJob: ExpireEmptySessionsJob

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @AfterEach
    fun cleanUpCreatedRestaurants() {
        createdRestaurantIds.forEach { restaurantId ->
            jdbcTemplate.update("DELETE FROM restaurant WHERE id = ?", restaurantId)
        }
        createdRestaurantIds.clear()
    }

    /** Verifica que la versión publicada coincida con los metadatos del build y el contrato. */
    @Test
    fun `version endpoint returns build and contract metadata`() {
        mockMvc.perform(get("/version"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.build").value("0.0.1-SNAPSHOT"))
            .andExpect(jsonPath("$.contractVersion").value("0.4.1"))
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
            .andExpect(jsonPath("$.code").value("JOIN_CODE_INVALID"))

        mockMvc.perform(
            post("/public/tables/$tableCode/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":"Second host"}"""),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SESSION_ALREADY_OPEN"))

        val inactiveTableCode = createTable()
        jdbcTemplate.update("UPDATE dining_table SET active = FALSE WHERE table_code = ?", inactiveTableCode)
        mockMvc.perform(
            post("/public/tables/$inactiveTableCode/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":"Unavailable host"}"""),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("TABLE_UNAVAILABLE"))
    }

    @Test
    fun `join code attempts block at five and unblock after the block expires`() {
        val tableCode = createTable()
        val opened = openSession(tableCode)

        repeat(4) {
            postJoin(tableCode, "ZZZZZZ", "192.0.2.10")
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("JOIN_CODE_INVALID"))
        }
        postJoin(tableCode, "AAAAAA", "192.0.2.10")
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("JOIN_CODE_RATE_LIMITED"))
            .andExpect(jsonPath("$.extra.retryAfterSec").value(600))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "600"))

        postJoin(tableCode, "AAAAAA", "192.0.2.10")
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("JOIN_CODE_RATE_LIMITED"))
        postJoin(tableCode, "AAAAAA", "192.0.2.11")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("JOIN_CODE_INVALID"))

        jdbcTemplate.update(
            """
            UPDATE join_code_attempt
            SET blocked_until = ?, window_started_at = ?
            WHERE table_id = (SELECT id FROM dining_table WHERE table_code = ?)
              AND client_key = ?
            """.trimIndent(),
            java.sql.Timestamp.from(clock.instant().minusSeconds(1)),
            java.sql.Timestamp.from(clock.instant().minusSeconds(601)),
            tableCode,
            sha256Hex("192.0.2.10"),
        )
        postJoin(tableCode, "AAAAAA", "192.0.2.10")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("JOIN_CODE_INVALID"))
        postJoin(tableCode, opened.joinCode, "192.0.2.10")
            .andExpect(status().isCreated)
    }

    @Test
    fun `concurrent invalid join attempts cannot bypass the per table limit`() {
        val tableCode = createTable()
        openSession(tableCode)
        val executor = Executors.newFixedThreadPool(8)
        val statuses = try {
            executor.invokeAll((1..8).map {
                Callable {
                    postJoin(tableCode, "ZZZZZZ", "192.0.2.20")
                        .andReturn().response.status
                }
            }).map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(4, statuses.count { it == 403 })
        assertEquals(4, statuses.count { it == 429 })
    }

    @Test
    fun `joining updates session activity timestamp`() {
        val tableCode = createTable()
        val opened = openSession(tableCode)
        val staleAt = clock.instant().minusSeconds(600)
        jdbcTemplate.update(
            "UPDATE table_session SET last_activity_at = ? WHERE id = ?",
            java.sql.Timestamp.from(staleAt),
            opened.sessionId,
        )

        postJoin(tableCode, opened.joinCode, "192.0.2.12")
            .andExpect(status().isCreated)

        val lastActivity = jdbcTemplate.queryForObject(
            "SELECT last_activity_at FROM table_session WHERE id = ?",
            java.sql.Timestamp::class.java,
            opened.sessionId,
        )!!.toInstant()
        assertTrue(lastActivity.isAfter(staleAt))
        assertTrue(lastActivity <= clock.instant())
    }

    @Test
    fun `idle expiry uses each restaurant ttl and preserves sessions with pending or billable orders`() {
        val shortTtlRestaurant = createRestaurant(5)
        val longTtlRestaurant = createRestaurant(10)
        val now = clock.instant()
        val shortTtlTable = createTable(shortTtlRestaurant)
        val longTtlTable = createTable(longTtlRestaurant)
        val exactBoundary = openSession(shortTtlTable)
        val notDue = openSession(createTable(shortTtlRestaurant))
        val longTtl = openSession(longTtlTable)
        val pendingOrder = openSession(createTable(shortTtlRestaurant))
        val acceptedOrder = openSession(createTable(shortTtlRestaurant))

        listOf(exactBoundary, notDue, longTtl, pendingOrder, acceptedOrder).forEach {
            jdbcTemplate.update(
                "UPDATE table_session SET last_activity_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now.minusSeconds(600)),
                it.sessionId,
            )
        }
        jdbcTemplate.update(
            "UPDATE table_session SET last_activity_at = ? WHERE id = ?",
            java.sql.Timestamp.from(now.minusSeconds(300)),
            exactBoundary.sessionId,
        )
        jdbcTemplate.update(
            "UPDATE table_session SET last_activity_at = ? WHERE id = ?",
            java.sql.Timestamp.from(now.minusSeconds(300).plusNanos(1000)),
            notDue.sessionId,
        )
        jdbcTemplate.update(
            "UPDATE table_session SET last_activity_at = ? WHERE id = ?",
            java.sql.Timestamp.from(now.minusSeconds(599)),
            longTtl.sessionId,
        )
        attachOrder(pendingOrder, shortTtlRestaurant, "PENDING_REVIEW", now)
        attachOrder(acceptedOrder, shortTtlRestaurant, "ACCEPTED", now)

        expireEmptySessionsJob.expireIdleSessionsAt(now)

        assertEquals("CLOSED", sessionStatus(exactBoundary.sessionId))
        assertEquals("EXPIRED_EMPTY", closeReason(exactBoundary.sessionId))
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'SESSION_EXPIRED' AND target_id = ?",
                Int::class.java,
                exactBoundary.sessionId,
            ),
        )
        assertEquals("OPEN", sessionStatus(notDue.sessionId))
        assertEquals("OPEN", sessionStatus(longTtl.sessionId))
        assertEquals("OPEN", sessionStatus(pendingOrder.sessionId))
        assertEquals("OPEN", sessionStatus(acceptedOrder.sessionId))
    }

    @Test
    fun `concurrent idle expiry executions close an eligible session once`() {
        val restaurantId = createRestaurant(1)
        val tableCode = createTable(restaurantId)
        val opened = openSession(tableCode)
        val now = clock.instant()
        jdbcTemplate.update(
            "UPDATE table_session SET last_activity_at = ? WHERE id = ?",
            java.sql.Timestamp.from(now.minusSeconds(120)),
            opened.sessionId,
        )

        val executor = Executors.newFixedThreadPool(2)
        val closedCounts = try {
            executor.invokeAll(
                listOf(
                    Callable { expireEmptySessionsJob.expireIdleSessionsAt(now) },
                    Callable { expireEmptySessionsJob.expireIdleSessionsAt(now) },
                ),
            ).map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, closedCounts.sum())
        assertEquals("CLOSED", sessionStatus(opened.sessionId))
    }

    @Test
    fun `join activity lock does not deadlock expiry or discard an active attempt`() {
        val restaurantId = createRestaurant(1)
        val tableCode = createTable(restaurantId)
        val opened = openSession(tableCode)
        val clientKey = sha256Hex("192.0.2.30")
        val now = clock.instant()
        jdbcTemplate.update(
            "UPDATE table_session SET last_activity_at = ? WHERE id = ?",
            java.sql.Timestamp.from(now.minusSeconds(120)),
            opened.sessionId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO join_code_attempt (table_id, client_key, failure_count, window_started_at)
            SELECT id, ?, 2, ? FROM dining_table WHERE table_code = ?
            """.trimIndent(),
            clientKey,
            java.sql.Timestamp.from(now.minusSeconds(1200)),
            tableCode,
        )

        val executor = Executors.newFixedThreadPool(3)
        val releaseAttemptLock = CountDownLatch(1)
        val attemptLocked = CountDownLatch(1)
        val holder = executor.submit {
            TransactionTemplate(transactionManager).executeWithoutResult {
                jdbcTemplate.queryForObject(
                    """
                    SELECT failure_count FROM join_code_attempt
                    WHERE table_id = (SELECT id FROM dining_table WHERE table_code = ?)
                      AND client_key = ?
                    FOR UPDATE
                    """.trimIndent(),
                    Int::class.java,
                    tableCode,
                    clientKey,
                )
                attemptLocked.countDown()
                check(releaseAttemptLock.await(15, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(attemptLocked.await(10, TimeUnit.SECONDS))
            val join = executor.submit(Callable {
                postJoin(tableCode, opened.joinCode, "192.0.2.30").andReturn().response
            })
            val expiry = executor.submit { expireEmptySessionsJob.expireIdleSessionsAt(now) }
            expiry.get(10, TimeUnit.SECONDS)
            assertEquals(0, expireEmptySessionsJob.cleanupExpiredJoinCodeAttemptsAt(now))
            assertEquals("CLOSED", sessionStatus(opened.sessionId))

            releaseAttemptLock.countDown()
            val joinResponse = join.get(10, TimeUnit.SECONDS)
            assertEquals(409, joinResponse.status)
            assertEquals("SESSION_CLOSED", JsonPath.read(joinResponse.contentAsString, "$.code"))
            assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_participant WHERE session_id = ?",
                Int::class.java,
                opened.sessionId,
            ))
            assertEquals(1, jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM join_code_attempt
                WHERE table_id = (SELECT id FROM dining_table WHERE table_code = ?)
                  AND client_key = ? AND failure_count = 0
                """.trimIndent(),
                Int::class.java,
                tableCode,
                clientKey,
            ))
        } finally {
            releaseAttemptLock.countDown()
            holder.get(10, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
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
        val staleActivity = clock.instant().minusSeconds(3600)
        jdbcTemplate.update(
            "UPDATE table_session SET last_activity_at = ? WHERE id = ?",
            java.sql.Timestamp.from(staleActivity),
            sessionId,
        )
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
        val lastActivity = jdbcTemplate.queryForObject(
            "SELECT last_activity_at FROM table_session WHERE id = ?",
            java.sql.Timestamp::class.java,
            sessionId,
        )!!.toInstant()
        assertTrue(lastActivity.isAfter(staleActivity))
        assertTrue(!lastActivity.isBefore(clock.instant().minusSeconds(1800)))

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

    private data class OpenedSession(val sessionId: UUID, val participantId: UUID, val joinCode: String)

    private fun openSession(tableCode: String): OpenedSession {
        val response = mockMvc.perform(
            post("/public/tables/$tableCode/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":"Expiry test"}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return OpenedSession(
            UUID.fromString(JsonPath.read(response, "$.session.id")),
            UUID.fromString(JsonPath.read(response, "$.participant.id")),
            JsonPath.read(response, "$.joinCode"),
        )
    }

    private fun postJoin(tableCode: String, joinCode: String, remoteAddress: String) =
        mockMvc.perform(
            post("/public/tables/$tableCode/join")
                .with { request -> request.remoteAddr = remoteAddress; request }
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":"Join attempt","joinCode":"$joinCode"}"""),
        )

    private fun createRestaurant(sessionIdleTtlMin: Int): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO restaurant (id, name, timezone, currency) VALUES (?, ?, ?, ?)",
            id,
            "Expiry test $id",
            "UTC",
            "ARS",
        )
        jdbcTemplate.update(
            "INSERT INTO restaurant_settings (restaurant_id, session_idle_ttl_min) VALUES (?, ?)",
            id,
            sessionIdleTtlMin,
        )
        createdRestaurantIds += id
        return id
    }

    private fun createTable(restaurantId: UUID): String {
        val id = UUID.randomUUID()
        val code = "COV-${UUID.randomUUID()}"
        jdbcTemplate.update(
            "INSERT INTO dining_table (id, restaurant_id, label, table_code) VALUES (?, ?, ?, ?)",
            id,
            restaurantId,
            "Coverage $code",
            code,
        )
        return code
    }

    private fun createTable(): String =
        createTable(UUID.fromString("6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68"))

    private fun attachOrder(session: OpenedSession, restaurantId: UUID, status: String, now: Instant) {
        jdbcTemplate.update(
            """
            INSERT INTO customer_order (
                id, restaurant_id, session_id, participant_id, status, display_code, summary, total_minor, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            restaurantId,
            session.sessionId,
            session.participantId,
            status,
            "#EXP",
            "Expiry test order",
            100L,
            java.sql.Timestamp.from(now),
        )
    }

    private fun sessionStatus(sessionId: UUID): String =
        jdbcTemplate.queryForObject("SELECT status FROM table_session WHERE id = ?", String::class.java, sessionId)!!

    private fun closeReason(sessionId: UUID): String? =
        jdbcTemplate.queryForObject("SELECT close_reason FROM table_session WHERE id = ?", String::class.java, sessionId)

    private fun sha256Hex(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

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
