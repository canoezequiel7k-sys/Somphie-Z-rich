package com.somphie.identity.api

import com.jayway.jsonpath.JsonPath
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.dao.DataIntegrityViolationException
import org.hamcrest.Matchers.hasItem
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import com.somphie.events.DomainEvent
import com.somphie.events.DomainEventType
import com.somphie.events.EventAggregateType
import com.somphie.events.EventOutboxService
import com.somphie.events.EventRetentionJob
import com.somphie.events.EventStreamService
import com.somphie.events.EventVersion
import com.somphie.events.StoredDomainEvent
import com.somphie.floor.application.GuestDataRetentionService
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.idempotency.IdempotencyRetentionService

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["DEMO_MODE=true"],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class IdentityIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    private val eventHttpClient: HttpClient = HttpClient.newHttpClient()

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var eventOutbox: EventOutboxService

    @Autowired
    private lateinit var eventStreamService: EventStreamService

    @Autowired
    private lateinit var eventRetentionJob: EventRetentionJob

    @Autowired
    private lateinit var guestDataRetentionService: GuestDataRetentionService

    @Autowired
    private lateinit var idempotencyRetentionService: IdempotencyRetentionService

    @Autowired
    private lateinit var eventStreamScheduler: ScheduledThreadPoolExecutor

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun seedAccount() {
        jdbc.update("DELETE FROM customer_order WHERE created_by_user_id = ?", USER_ID)
        jdbc.update("DELETE FROM bill_adjustment WHERE restaurant_id = ?", RESTAURANT_ID)
        jdbc.update("DELETE FROM auth_login_attempt")
        jdbc.update("DELETE FROM audit_event WHERE target_id = ?", USER_ID)
        jdbc.update("DELETE FROM refresh_token WHERE user_id = ?", USER_ID)
        jdbc.update("DELETE FROM user_station WHERE user_id = ?", USER_ID)
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
        jdbc.update(
            "UPDATE product SET available = TRUE WHERE id = ?",
            UUID.fromString("9a2b4c6d-8e0f-4a1b-b3c5-d7e9f1a3b5c7"),
        )
        jdbc.update(
            "UPDATE product_option SET available = TRUE WHERE id = ?",
            UUID.fromString("e5f6a7b8-c9d0-4e1f-9a2b-3c4d5e6f7081"),
        )
    }

    @Test
    fun `guest retention anonymizes old participant names and deletes old waiter calls`() {
        val fixture = createGuestSession()
        val oldParticipantId = UUID.fromString(fixture.participantId)
        val recentParticipantId = UUID.randomUUID()
        val now = Instant.now()
        jdbc.update(
            "UPDATE session_participant SET joined_at = ? WHERE id = ?",
            java.sql.Timestamp.from(now.minus(java.time.Duration.ofDays(400))),
            oldParticipantId,
        )
        jdbc.update(
            """
            INSERT INTO session_participant (id, session_id, display_name, role, token_hash, joined_at)
            VALUES (?, ?, ?, 'MEMBER', ?, ?)
            """.trimIndent(),
            recentParticipantId,
            UUID.fromString(fixture.sessionId),
            "Recent guest",
            UUID.randomUUID().toString(),
            java.sql.Timestamp.from(now.minus(java.time.Duration.ofDays(300))),
        )
        val oldCallId = UUID.randomUUID()
        val recentCallId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO waiter_call
                (id, session_id, participant_id, reason, status, created_at, expires_at)
            VALUES (?, ?, ?, 'WATER', 'EXPIRED', ?, ?), (?, ?, ?, 'OTHER', 'EXPIRED', ?, ?)
            """.trimIndent(),
            oldCallId,
            UUID.fromString(fixture.sessionId),
            oldParticipantId,
            java.sql.Timestamp.from(now.minus(java.time.Duration.ofDays(31))),
            java.sql.Timestamp.from(now.minus(java.time.Duration.ofDays(31))),
            recentCallId,
            UUID.fromString(fixture.sessionId),
            recentParticipantId,
            java.sql.Timestamp.from(now.minus(java.time.Duration.ofDays(29))),
            java.sql.Timestamp.from(now.minus(java.time.Duration.ofDays(29))),
        )

        val result = guestDataRetentionService.applyRetention()

        assertEquals(1, result.participantNamesAnonymized)
        assertEquals(1, result.waiterCallsDeleted)
        assertEquals(
            "Anónimo",
            jdbc.queryForObject(
                "SELECT display_name FROM session_participant WHERE id = ?",
                String::class.java,
                oldParticipantId,
            ),
        )
        assertEquals(
            "Recent guest",
            jdbc.queryForObject(
                "SELECT display_name FROM session_participant WHERE id = ?",
                String::class.java,
                recentParticipantId,
            ),
        )
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM waiter_call WHERE id = ?", Int::class.java, oldCallId))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM waiter_call WHERE id = ?", Int::class.java, recentCallId))
    }

    @Test
    fun `idempotency retention deletes records after 24 hours and keeps recent records`() {
        val oldCompletedKey = UUID.randomUUID().toString()
        val oldInProgressKey = UUID.randomUUID().toString()
        val recentKey = UUID.randomUUID().toString()
        val now = Instant.now()
        jdbc.update(
            """
            INSERT INTO idempotency_record
                (scope, key, request_hash, status, response_status, response_body, created_at)
            VALUES
                (?, ?, 'hash-a', 'COMPLETED', 200, '{}', ?),
                (?, ?, 'hash-b', 'IN_PROGRESS', NULL, NULL, ?),
                (?, ?, 'hash-c', 'COMPLETED', 200, '{}', ?)
            """.trimIndent(),
            "retention-test",
            oldCompletedKey,
            java.sql.Timestamp.from(now.minus(java.time.Duration.ofHours(25))),
            "retention-test",
            oldInProgressKey,
            java.sql.Timestamp.from(now.minus(java.time.Duration.ofHours(25))),
            "retention-test",
            recentKey,
            java.sql.Timestamp.from(now.minus(java.time.Duration.ofHours(23))),
        )

        val deleted = idempotencyRetentionService.deleteExpiredRecords()

        assertEquals(2, deleted)
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_record WHERE scope = ? AND key IN (?, ?)",
                Int::class.java,
                "retention-test",
                oldCompletedKey,
                oldInProgressKey,
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_record WHERE scope = ? AND key = ?",
                Int::class.java,
                "retention-test",
                recentKey,
            ),
        )
    }

    @Test
    fun `identity endpoints require a valid staff access token`() {
        mockMvc.perform(get("/me"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.traceId").isNotEmpty)
    }

    @Test
    fun `staff event stream requires an authenticated staff token`() {
        mockMvc.perform(get("/events/stream").param("scope", "staff"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
    }

    @Test
    fun `station staff without assignments cannot replay order events`() {
        val fixture = createGuestSession()
        jdbc.update("DELETE FROM user_role WHERE user_id = ?", USER_ID)
        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'KITCHEN')", USER_ID)
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val access = eventStreamService.authorize("staff", null, jwtDecoder.decode(token))
        val cursor = eventCursor()
        val orderId = UUID.fromString(createOrder(fixture))
        addOrderItem(orderId.toString(), fixture)

        TransactionTemplate(transactionManager).execute {
            eventOutbox.append(
                DomainEvent(
                    restaurantId = RESTAURANT_ID,
                    type = DomainEventType.ORDER_CREATED,
                    aggregateType = EventAggregateType.ORDER,
                    aggregateId = orderId,
                    version = EventVersion.Increment,
                    sessionId = UUID.fromString(fixture.sessionId),
                ),
            )
            eventOutbox.append(
                DomainEvent(
                    restaurantId = RESTAURANT_ID,
                    type = DomainEventType.CATALOG_UPDATED,
                    aggregateType = EventAggregateType.CATALOG,
                    aggregateId = UUID.randomUUID(),
                    version = EventVersion.Increment,
                ),
            )
        }

        val events = eventStreamService.eventsAfter(access, cursor)
        assertFalse(events.any { it.type == DomainEventType.ORDER_CREATED })
        assertTrue(events.any { it.type == DomainEventType.CATALOG_UPDATED })
        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'BAR')", USER_ID)
        val barKitchenAccess = eventStreamService.authorize("staff", null, jwtDecoder.decode(token))
        assertTrue(eventStreamService.eventsAfter(barKitchenAccess, cursor).any { it.type == DomainEventType.ORDER_CREATED })
        jdbc.update("DELETE FROM user_role WHERE user_id = ? AND role_code = 'BAR'", USER_ID)
        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'OWNER')", USER_ID)
        val ownerKitchenAccess = eventStreamService.authorize("staff", null, jwtDecoder.decode(token))
        assertTrue(eventStreamService.eventsAfter(ownerKitchenAccess, cursor).any { it.type == DomainEventType.ORDER_CREATED })
        jdbc.update("DELETE FROM user_role WHERE user_id = ? AND role_code = 'OWNER'", USER_ID)

        val kitchenStationId = jdbc.queryForObject(
            "SELECT id FROM station WHERE restaurant_id = ? AND code = 'KITCHEN'",
            UUID::class.java,
            RESTAURANT_ID,
        )!!
        jdbc.update("INSERT INTO user_station (user_id, station_id) VALUES (?, ?)", USER_ID, kitchenStationId)
        jdbc.update("UPDATE order_item SET station_id = ? WHERE order_id = ?", kitchenStationId, orderId)
        val assignedAccess = eventStreamService.authorize("staff", null, jwtDecoder.decode(token))
        val assignedCursor = eventCursor()
        val permittedEvent = UUID.randomUUID()
        TransactionTemplate(transactionManager).execute {
            eventOutbox.append(
                DomainEvent(
                    restaurantId = RESTAURANT_ID,
                    type = DomainEventType.ORDER_CREATED,
                    aggregateType = EventAggregateType.ORDER,
                    aggregateId = orderId,
                    version = EventVersion.Increment,
                    sessionId = UUID.fromString(fixture.sessionId),
                ),
            )
            eventOutbox.append(
                DomainEvent(
                    restaurantId = RESTAURANT_ID,
                    type = DomainEventType.ITEM_UPDATED,
                    aggregateType = EventAggregateType.ITEM,
                    aggregateId = permittedEvent,
                    version = EventVersion.Increment,
                    stationId = kitchenStationId,
                ),
            )
            eventOutbox.append(
                DomainEvent(
                    restaurantId = RESTAURANT_ID,
                    type = DomainEventType.ITEM_UPDATED,
                    aggregateType = EventAggregateType.ITEM,
                    aggregateId = UUID.randomUUID(),
                    version = EventVersion.Increment,
                    stationId = BAR_STATION_ID,
                ),
            )
            eventOutbox.append(
                DomainEvent(
                    restaurantId = RESTAURANT_ID,
                    type = DomainEventType.ITEM_UPDATED,
                    aggregateType = EventAggregateType.ITEM,
                    aggregateId = UUID.randomUUID(),
                    version = EventVersion.Increment,
                ),
            )
        }

        val assignedEvents = eventStreamService.eventsAfter(assignedAccess, assignedCursor)
        assertFalse(assignedEvents.any { it.type == DomainEventType.ORDER_CREATED })
        assertEquals(listOf(permittedEvent), assignedEvents.map { it.aggregateId })

        jdbc.update("UPDATE customer_order SET status = 'ACCEPTED' WHERE id = ?", orderId)
        jdbc.update(
            "UPDATE order_item SET status = 'QUEUED', queued_at = CURRENT_TIMESTAMP WHERE order_id = ?",
            orderId,
        )
        val acceptedCursor = eventCursor()
        TransactionTemplate(transactionManager).execute {
            eventOutbox.append(
                DomainEvent(
                    restaurantId = RESTAURANT_ID,
                    type = DomainEventType.ORDER_UPDATED,
                    aggregateType = EventAggregateType.ORDER,
                    aggregateId = orderId,
                    version = EventVersion.Increment,
                    sessionId = UUID.fromString(fixture.sessionId),
                ),
            )
        }
        val acceptedEvents = eventStreamService.eventsAfter(assignedAccess, acceptedCursor)
        assertTrue(
            acceptedEvents.any { it.type == DomainEventType.ORDER_UPDATED && it.aggregateId == orderId },
            "Expected the accepted station order event, got $acceptedEvents",
        )
    }

    @Test
    fun `session event stream rejects unknown guest tokens`() {
        mockMvc.perform(
            get("/events/stream")
                .param("scope", "session")
                .header("Authorization", "Bearer gt_invalid"),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
    }

    @Test
    fun `event stream rejects malformed cursors before opening a connection`() {
        mockMvc.perform(
            get("/events/stream")
                .param("scope", "session")
                .header("Authorization", "Bearer gt_invalid")
                .header("Last-Event-ID", "not-a-number"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `event stream rejects cursors ahead of the restaurant log`() {
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val latestCursor = eventCursor()

        mockMvc.perform(
            get("/events/stream")
                .param("scope", "staff")
                .header("Authorization", "Bearer $token")
                .header("Last-Event-ID", (latestCursor + 1).toString()),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `event stream rejects cursors older than the persisted retention watermark`() {
        jdbc.update(
            """
            INSERT INTO event_retention (restaurant_id, last_deleted_event_id)
            VALUES (?, 2)
            ON CONFLICT (restaurant_id) DO UPDATE SET last_deleted_event_id = 2
            """.trimIndent(),
            RESTAURANT_ID,
        )
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")

        mockMvc.perform(
            get("/events/stream")
                .param("scope", "staff")
                .header("Authorization", "Bearer $token")
                .header("Last-Event-ID", "1"),
        )
            .andExpect(status().isGone)
            .andExpect(jsonPath("$.code").value("EVENT_ID_TOO_OLD"))
    }

    @Test
    fun `revoking a participant closes their active event stream`() {
        val fixture = createGuestSession()
        val member = createMemberFixture(fixture)
        val cursor = jdbc.queryForObject(
            "SELECT COALESCE(MAX(id), 0) FROM event_log WHERE restaurant_id = ?",
            Long::class.java,
            RESTAURANT_ID,
        ) ?: 0L
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/events/stream?scope=session"))
                .header("Authorization", "Bearer ${member.second}")
                .header("Last-Event-ID", cursor.toString())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        assertEquals(200, response.statusCode())

        val streamBody = response.body()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val streamClosed = reader.submit(Callable { streamBody.use { it.readBytes() } })
            mockMvc.perform(
                delete("/sessions/current/participants/${member.first}")
                    .header("Authorization", "Bearer ${fixture.token}"),
            ).andExpect(status().isOk)

            streamClosed.get(10, TimeUnit.SECONDS)
        } finally {
            streamBody.close()
            reader.shutdownNow()
        }
    }

    @Test
    fun `removing staff permissions closes their active event stream`() {
        val cursor = eventCursor()
        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val baselineTasks = scheduledStreamCount()
        val response = openStaffEventStream(staffToken, cursor)
        val streamBody = response.body()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val streamClosed = reader.submit(Callable { streamBody.use { it.readBytes() } })
            jdbc.update("DELETE FROM user_role WHERE user_id = ?", USER_ID)

            val output = streamClosed.get(10, TimeUnit.SECONDS)
            val message = String(output, Charsets.UTF_8)
            assertFalse(message.contains("event:"), "Revoked staff received an SSE event: $message")
            assertFalse(message.contains("data:"), "Revoked staff received SSE event data: $message")
            awaitScheduledStreamCountAtMost(baselineTasks, 10)
        } finally {
            streamBody.close()
            reader.shutdownNow()
        }
    }

    @Test
    fun `event stream replays committed events after reconnecting from last event id`() {
        val fixture = createGuestSession()
        val cursor = eventCursor()
        val disconnected = openSessionEventStream(fixture.token, cursor)
        disconnected.body().close()

        val expected = appendSessionEvent(UUID.fromString(fixture.sessionId))
        val reconnected = openSessionEventStream(fixture.token, cursor)
        try {
            val message = Executors.newSingleThreadExecutor().use { reader ->
                reader.submit(Callable { readSseMessage(reconnected.body()) })
                    .get(10, TimeUnit.SECONDS)
            }
            assertTrue(message.contains("id:${expected.eventId}"), "Unexpected replayed SSE message: $message")
            assertTrue(message.contains("event:${expected.type.wireValue}"))
        } finally {
            reconnected.body().close()
        }
    }

    @Test
    fun `event stream sends a heartbeat comment after the idle interval`() {
        val fixture = createGuestSession()
        val response = openSessionEventStream(fixture.token, eventCursor())
        val reader = Executors.newSingleThreadExecutor()
        try {
            val message = reader.submit(Callable { readSseMessage(response.body()) })
                .get(35, TimeUnit.SECONDS)
            assertEquals(":heartbeat", message)
        } finally {
            response.body().close()
            reader.shutdownNow()
        }
    }

    @Test
    fun `fifty concurrent event streams receive one committed event and release resources on close`() {
        val fixture = createGuestSession()
        val member = createMemberFixture(fixture)
        val cursor = eventCursor()
        val sessionId = UUID.fromString(fixture.sessionId)
        val baselineTasks = scheduledStreamCount()
        val requests = (1..CONCURRENT_SSE_CONNECTIONS).map {
            HttpRequest.newBuilder(URI("http://localhost:$port/events/stream?scope=session"))
                .header("Authorization", listOf("Bearer", member.second).joinToString(" "))
                .header("Last-Event-ID", cursor.toString())
                .GET()
                .build()
        }
        val clientExecutor = Executors.newFixedThreadPool(CONCURRENT_SSE_CONNECTIONS)
        val client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .executor(clientExecutor)
            .build()
        val openings = requests.map {
            client.sendAsync(it, HttpResponse.BodyHandlers.ofInputStream())
        }
        val responses = mutableListOf<HttpResponse<InputStream>>()
        val readers = Executors.newFixedThreadPool(CONCURRENT_SSE_CONNECTIONS)
        try {
            CompletableFuture.allOf(*openings.toTypedArray()).get(30, TimeUnit.SECONDS)
            responses.addAll(openings.map { it.join() })
            assertTrue(responses.all { it.statusCode() == 200 }, "All 50 SSE connections must be accepted")

            val readersReady = CountDownLatch(CONCURRENT_SSE_CONNECTIONS)
            val deliveredEvents = responses.map { response ->
                readers.submit(Callable {
                    readersReady.countDown()
                    readSseEvent(
                        BufferedReader(InputStreamReader(response.body(), Charsets.UTF_8)),
                        "session.updated",
                    )
                })
            }
            assertTrue(readersReady.await(10, TimeUnit.SECONDS), "All SSE readers must be waiting before publish")

            val expected = appendSessionEvent(sessionId)
            assertEquals(
                1,
                jdbc.queryForObject(
                    """
                    SELECT count(*) FROM event_log
                    WHERE restaurant_id = ? AND id > ? AND type = 'session.updated' AND session_id = ?
                    """.trimIndent(),
                    Int::class.java,
                    RESTAURANT_ID,
                    cursor,
                    sessionId,
                ),
            )
            deliveredEvents.forEach { delivered ->
                val message = delivered.get(30, TimeUnit.SECONDS)
                assertTrue(message.lineSequence().any { it == "id:${expected.eventId}" })
                assertTrue(message.lineSequence().any { it == "event:${expected.type.wireValue}" })
            }
        } finally {
            openings.forEach { opening ->
                if (opening.isDone && !opening.isCompletedExceptionally && !opening.isCancelled) {
                    opening.getNow(null)?.let { response ->
                        if (responses.none { it === response }) responses += response
                    }
                } else {
                    opening.cancel(true)
                }
            }
            try {
                // El cierre HTTP debe ocurrir antes de medir las tareas del servidor;
                // revocar al participante fuerza el siguiente sondeo a completar cada stream.
                responses.forEach { it.body().close() }
                mockMvc.perform(
                    delete("/sessions/current/participants/${member.first}")
                        .header("Authorization", listOf("Bearer", fixture.token).joinToString(" ")),
                ).andExpect(status().isOk)
                awaitScheduledStreamCountAtMost(baselineTasks, 10)
            } finally {
                responses.forEach { it.body().close() }
                client.shutdownNow()
                clientExecutor.shutdownNow()
                assertTrue(clientExecutor.awaitTermination(10, TimeUnit.SECONDS), "HTTP client executor must stop")
                readers.shutdownNow()
                assertTrue(readers.awaitTermination(10, TimeUnit.SECONDS), "SSE reader threads must stop after close")
            }
        }
    }

    @Test
    fun `session event replay is isolated to the authorized session`() {
        jdbc.update("DELETE FROM event_retention WHERE restaurant_id = ?", RESTAURANT_ID)
        jdbc.update(
            "INSERT INTO restaurant (id, name, timezone, currency) VALUES (?, 'Other stream tenant', 'UTC', 'CHF') ON CONFLICT (id) DO NOTHING",
            OTHER_RESTAURANT_ID,
        )
        jdbc.update(
            "INSERT INTO restaurant_settings (restaurant_id) VALUES (?) ON CONFLICT (restaurant_id) DO NOTHING",
            OTHER_RESTAURANT_ID,
        )
        val first = createGuestSession()
        val second = createGuestSession()
        val foreign = createGuestSession(OTHER_RESTAURANT_ID)
        val baselineCursor = jdbc.queryForObject(
            "SELECT COALESCE(MAX(id), 0) FROM event_log WHERE restaurant_id = ?",
            Long::class.java,
            RESTAURANT_ID,
        ) ?: 0L
        TransactionTemplate(transactionManager).executeWithoutResult {
            listOf(first, second).forEach { fixture ->
                eventOutbox.append(
                    DomainEvent(
                        restaurantId = RESTAURANT_ID,
                        type = DomainEventType.SESSION_UPDATED,
                        aggregateType = EventAggregateType.SESSION,
                        aggregateId = UUID.fromString(fixture.sessionId),
                        version = EventVersion.Increment,
                        sessionId = UUID.fromString(fixture.sessionId),
                    ),
                )
            }

            @Test
            fun `session creation joining and revocation publish durable updates`() {
                val fixture = createGuestSession()
                val joinCode = jdbc.queryForObject(
                    "SELECT join_code FROM table_session WHERE id = ?",
                    String::class.java,
                    UUID.fromString(fixture.sessionId),
                )!!
                val joined = mockMvc.perform(
                    post("/public/tables/${fixture.tableCode}/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"displayName":"Joining guest","joinCode":"$joinCode"}"""),
                ).andExpect(status().isCreated)
                    .andReturn().response.contentAsString
                val joinedParticipantId = json(joined, "$.participant.id")

                mockMvc.perform(
                    delete("/sessions/current/participants/$joinedParticipantId")
                        .header("Authorization", "Bearer ${fixture.token}"),
                ).andExpect(status().isOk)

                val eventTypes = jdbc.queryForList(
                    "SELECT type FROM event_log WHERE session_id = ? ORDER BY id",
                    String::class.java,
                    UUID.fromString(fixture.sessionId),
                )
                assertEquals(
                    listOf(
                        DomainEventType.SESSION_UPDATED.wireValue,
                        DomainEventType.TABLE_UPDATED.wireValue,
                        DomainEventType.SESSION_UPDATED.wireValue,
                        DomainEventType.SESSION_UPDATED.wireValue,
                    ),
                    eventTypes,
                )
            }
            eventOutbox.append(
                DomainEvent(
                    restaurantId = OTHER_RESTAURANT_ID,
                    type = DomainEventType.SESSION_UPDATED,
                    aggregateType = EventAggregateType.SESSION,
                    aggregateId = UUID.fromString(foreign.sessionId),
                    version = EventVersion.Increment,
                    sessionId = UUID.fromString(foreign.sessionId),
                ),
            )
        }

        val access = eventStreamService.authorize("session", "Bearer ${first.token}", null)
        val replayed = eventStreamService.eventsAfter(access, baselineCursor)
        val firstSessionEvents = replayed.filter { it.sessionId == UUID.fromString(first.sessionId) }

        assertEquals(1, firstSessionEvents.size)
        assertEquals(DomainEventType.SESSION_UPDATED, firstSessionEvents.single().type)
        assertTrue(replayed.none { it.sessionId == UUID.fromString(second.sessionId) })
        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val staffAccess = eventStreamService.authorize("staff", null, jwtDecoder.decode(staffToken))
        assertTrue(
            eventStreamService.eventsAfter(staffAccess, 0).none {
                it.aggregateId == UUID.fromString(foreign.sessionId)
            },
        )
        jdbc.update("DELETE FROM user_role WHERE user_id = ?", USER_ID)
        assertEquals(
            ErrorCode.STREAM_SCOPE_FORBIDDEN,
            assertThrows(DomainException::class.java) {
                eventStreamService.authorize("staff", null, jwtDecoder.decode(staffToken))
            }.code,
        )

        jdbc.update(
            "UPDATE event_log SET occurred_at = CURRENT_TIMESTAMP - INTERVAL '49 hours' WHERE restaurant_id = ? AND id = ?",
            RESTAURANT_ID,
            firstSessionEvents.single().eventId,
        )
        eventRetentionJob.deleteExpiredEvents()
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM event_log WHERE restaurant_id = ? AND id = ?",
                Int::class.java,
                RESTAURANT_ID,
                firstSessionEvents.single().eventId,
            ),
        )
        assertEquals(
            ErrorCode.EVENT_ID_TOO_OLD,
            assertThrows(DomainException::class.java) { eventStreamService.ensureCursor(access, 0) }.code,
        )
    }

    @Test
    fun `staff endpoints require an authenticated account`() {
        mockMvc.perform(get("/staff/orders"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        mockMvc.perform(get("/staff/stations/$BAR_STATION_ID/queue"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
    }

    @Test
    fun `staff closes an empty session once and retries return the closed session`() {
        val fixture = createGuestSession()
        val cursorBeforeClose = eventCursor()
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val route = "/staff/sessions/${fixture.sessionId}/close"

        repeat(2) {
            mockMvc.perform(post(route).header("Authorization", "Bearer $token"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closeReason").value("EMPTY"))
                .andExpect(jsonPath("$.readOnly").value(true))
        }

        assertEquals(
            listOf(DomainEventType.SESSION_CLOSED.wireValue, DomainEventType.TABLE_UPDATED.wireValue),
            jdbc.queryForList(
                """
                SELECT type FROM event_log
                WHERE restaurant_id = ? AND session_id = ? AND id > ?
                  AND type IN ('session.closed', 'table.updated')
                ORDER BY id
                """.trimIndent(),
                String::class.java,
                RESTAURANT_ID,
                UUID.fromString(fixture.sessionId),
                cursorBeforeClose,
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM event_log WHERE session_id = ? AND type = 'session.closed'",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'SESSION_CLOSED' AND target_id = ?",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM table_session WHERE id = ? AND status = 'CLOSED' AND join_code IS NULL",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )

        val settled = createBillableSession()
        jdbc.update("UPDATE order_item SET status = 'DELIVERED' WHERE session_id = ?", UUID.fromString(settled.sessionId))
        mockMvc.perform(
            post("/staff/sessions/${settled.sessionId}/payments")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amountMinor":500,"tipMinor":0}"""),
        ).andExpect(status().isCreated)
        mockMvc.perform(
            post("/staff/sessions/${settled.sessionId}/close").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.closeReason").value("SETTLED"))
    }

    @Test
    fun `normal close rejects pending orders active items and nonzero balance without mutation`() {
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")

        val pending = createGuestSession()
        val pendingOrder = createOrder(pending)
        mockMvc.perform(
            post("/staff/sessions/${pending.sessionId}/close").header("Authorization", "Bearer $token"),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SESSION_HAS_PENDING_ORDERS"))
            .andExpect(jsonPath("$.extra.orderIds[0]").value(pendingOrder))
        assertSessionRemainsOpen(pending.sessionId)

        val active = createGuestSession()
        val activeOrder = createOrder(active)
        addOrderItem(activeOrder, active)
        jdbc.update("UPDATE customer_order SET status = 'ACCEPTED' WHERE id = ?", UUID.fromString(activeOrder))
        jdbc.update("UPDATE order_item SET status = 'QUEUED' WHERE order_id = ?", UUID.fromString(activeOrder))
        val activeItemId = jdbc.queryForObject(
            "SELECT id FROM order_item WHERE order_id = ?",
            UUID::class.java,
            UUID.fromString(activeOrder),
        )
        mockMvc.perform(
            post("/staff/sessions/${active.sessionId}/close").header("Authorization", "Bearer $token"),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SESSION_HAS_ACTIVE_ITEMS"))
            .andExpect(jsonPath("$.extra.itemIds[0]").value(activeItemId.toString()))
        assertSessionRemainsOpen(active.sessionId)

        val unpaid = createBillableSession()
        jdbc.update("UPDATE order_item SET status = 'DELIVERED' WHERE session_id = ?", UUID.fromString(unpaid.sessionId))
        mockMvc.perform(
            post("/staff/sessions/${unpaid.sessionId}/close").header("Authorization", "Bearer $token"),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SESSION_BALANCE_PENDING"))
            .andExpect(jsonPath("$.extra.balanceMinor").value(500))
        assertSessionRemainsOpen(unpaid.sessionId)
    }

    @Test
    fun `normal close enforces permission and tenant isolation`() {
        val fixture = createGuestSession()
        jdbc.update(
            """
            INSERT INTO restaurant (id, name, timezone, currency)
            VALUES (?, 'Foreign tenant', 'Europe/Zurich', 'CHF')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            OTHER_RESTAURANT_ID,
        )
        jdbc.update(
            "INSERT INTO restaurant_settings (restaurant_id) VALUES (?) ON CONFLICT (restaurant_id) DO NOTHING",
            OTHER_RESTAURANT_ID,
        )
        val foreign = createGuestSession(OTHER_RESTAURANT_ID)
        var token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")

        mockMvc.perform(
            post("/staff/sessions/${foreign.sessionId}/close").header("Authorization", "Bearer $token"),
        ).andExpect(status().isNotFound)
        assertSessionRemainsOpen(foreign.sessionId)

        jdbc.update("DELETE FROM user_role WHERE user_id = ?", USER_ID)
        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'KITCHEN')", USER_ID)
        token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        mockMvc.perform(
            post("/staff/sessions/${fixture.sessionId}/close").header("Authorization", "Bearer $token"),
        ).andExpect(status().isForbidden)
        assertSessionRemainsOpen(fixture.sessionId)
    }

    @Test
    fun `concurrent normal closes serialize and audit once`() {
        val fixture = createGuestSession()
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val calls = (1..2).map {
                executor.submit(Callable<Int> {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    mockMvc.perform(
                        post("/staff/sessions/${fixture.sessionId}/close")
                            .header("Authorization", "Bearer $token"),
                    ).andReturn().response.status
                })
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(listOf(200, 200), calls.map { it.get(10, TimeUnit.SECONDS) }.sorted())
        } finally {
            executor.shutdownNow()
        }
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'SESSION_CLOSED' AND target_id = ?",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )
    }

    @Test
    fun `forced close cancels pending and active work refunds principal and replays`() {
        val fixture = createBillableSession()
        val activeOrderId = jdbc.queryForObject(
            "SELECT id FROM customer_order WHERE session_id = ?",
            UUID::class.java,
            UUID.fromString(fixture.sessionId),
        )!!
        jdbc.update(
            "UPDATE customer_order SET status = 'IN_PREPARATION' WHERE id = ?",
            activeOrderId,
        )
        jdbc.update(
            "UPDATE order_item SET status = 'IN_PREPARATION', started_at = CURRENT_TIMESTAMP WHERE order_id = ?",
            activeOrderId,
        )
        val pendingOrderId = UUID.fromString(createOrder(fixture))
        mockMvc.perform(
            post("/sessions/current/payments")
                .header("Authorization", "Bearer ${fixture.token}")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amountMinor":500,"tipMinor":50,"method":"SIMULATED_ONLINE"}"""),
        ).andExpect(status().isCreated)

        jdbc.update("UPDATE user_role SET role_code = 'OWNER' WHERE user_id = ?", USER_ID)
        val ownerToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val key = UUID.randomUUID().toString()
        val route = "/staff/sessions/${fixture.sessionId}/force-close"
        val body = """{"reason":"Customer left before service"}"""
        val cursorBeforeClose = eventCursor()
        mockMvc.perform(
            post(route)
                .header("Authorization", "Bearer $ownerToken")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.closeReason").value("FORCED"))
            .andExpect(jsonPath("$.readOnly").value(true))

        mockMvc.perform(
            post(route)
                .header("Authorization", "Bearer $ownerToken")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isOk)
            .andExpect(header().string("Idempotent-Replayed", "true"))

        assertEquals(
            listOf("session.closed", "table.updated"),
            jdbc.queryForList(
                """
                SELECT type FROM event_log
                WHERE restaurant_id = ? AND session_id = ? AND id > ?
                  AND type IN ('session.closed', 'table.updated')
                ORDER BY id
                """.trimIndent(),
                String::class.java,
                RESTAURANT_ID,
                UUID.fromString(fixture.sessionId),
                cursorBeforeClose,
            ),
        )
        assertEquals(
            "CANCELLED",
            jdbc.queryForObject("SELECT status FROM customer_order WHERE id = ?", String::class.java, pendingOrderId),
        )
        assertEquals(
            "CANCELLED",
            jdbc.queryForObject("SELECT status FROM order_item WHERE order_id = ?", String::class.java, activeOrderId),
        )
        assertEquals(
            500L,
            jdbc.queryForObject(
                "SELECT SUM(amount_minor) FROM payment WHERE session_id = ? AND kind = 'REFUND'",
                Long::class.javaObjectType,
                UUID.fromString(fixture.sessionId),
            ),
        )
        assertEquals(
            50L,
            jdbc.queryForObject(
                "SELECT SUM(tip_minor) FROM payment WHERE session_id = ? AND kind = 'PAYMENT' AND status = 'SUCCEEDED'",
                Long::class.javaObjectType,
                UUID.fromString(fixture.sessionId),
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'SESSION_FORCE_CLOSED' AND target_id = ?",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'REFUND_CREATED' AND after_summary ->> 'source' = 'FORCED_SESSION_CLOSE' AND target_id IN (SELECT id FROM payment WHERE session_id = ? AND kind = 'REFUND')",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )
    }

    @Test
    fun `forced close rolls back cancellations and retries after refund persistence failure`() {
        val fixture = createBillableSession()
        val orderId = jdbc.queryForObject(
            "SELECT id FROM customer_order WHERE session_id = ?",
            UUID::class.java,
            UUID.fromString(fixture.sessionId),
        )!!
        jdbc.update("UPDATE customer_order SET status = 'IN_PREPARATION' WHERE id = ?", orderId)
        jdbc.update(
            "UPDATE order_item SET status = 'IN_PREPARATION', started_at = CURRENT_TIMESTAMP WHERE order_id = ?",
            orderId,
        )
        mockMvc.perform(
            post("/sessions/current/payments")
                .header("Authorization", "Bearer ${fixture.token}")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amountMinor":500,"tipMinor":0,"method":"SIMULATED_ONLINE"}"""),
        ).andExpect(status().isCreated)

        jdbc.update("UPDATE user_role SET role_code = 'OWNER' WHERE user_id = ?", USER_ID)
        val ownerToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val key = UUID.randomUUID().toString()
        jdbc.execute(
            """
            CREATE FUNCTION test_reject_force_close_refund() RETURNS trigger AS ${'$'}${'$'}
            BEGIN
                IF NEW.kind = 'REFUND' THEN RAISE EXCEPTION 'test refund failure'; END IF;
                RETURN NEW;
            END;
            ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbc.execute(
            "CREATE TRIGGER test_reject_force_close_refund BEFORE INSERT ON payment FOR EACH ROW EXECUTE FUNCTION test_reject_force_close_refund()",
        )
        try {
            mockMvc.perform(
                post("/staff/sessions/${fixture.sessionId}/force-close")
                    .header("Authorization", "Bearer $ownerToken")
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"reason":"Refund unavailable"}"""),
            ).andExpect(status().isInternalServerError)
        } finally {
            jdbc.execute("DROP TRIGGER test_reject_force_close_refund ON payment")
            jdbc.execute("DROP FUNCTION test_reject_force_close_refund()")
        }

        assertSessionRemainsOpen(fixture.sessionId)
        assertEquals("IN_PREPARATION", jdbc.queryForObject(
            "SELECT status FROM customer_order WHERE id = ?", String::class.java, orderId,
        ))
        assertEquals("IN_PREPARATION", jdbc.queryForObject(
            "SELECT status FROM order_item WHERE order_id = ?", String::class.java, orderId,
        ))
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE session_id = ? AND kind = 'REFUND'",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )
        mockMvc.perform(
            post("/staff/sessions/${fixture.sessionId}/force-close")
                .header("Authorization", "Bearer $ownerToken")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"Refund unavailable"}"""),
        ).andExpect(status().isOk)
    }

    @Test
    fun `forced close records remaining debt without creating a refund`() {
        val fixture = createBillableSession()
        val orderId = jdbc.queryForObject(
            "SELECT id FROM customer_order WHERE session_id = ?",
            UUID::class.java,
            UUID.fromString(fixture.sessionId),
        )!!
        jdbc.update("UPDATE customer_order SET status = 'DELIVERED' WHERE id = ?", orderId)
        jdbc.update(
            "UPDATE order_item SET status = 'DELIVERED', delivered_at = CURRENT_TIMESTAMP WHERE order_id = ?",
            orderId,
        )
        jdbc.update("UPDATE user_role SET role_code = 'OWNER' WHERE user_id = ?", USER_ID)
        val ownerToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")

        mockMvc.perform(
            post("/staff/sessions/${fixture.sessionId}/force-close")
                .header("Authorization", "Bearer $ownerToken")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"Customer leaves unpaid"}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.closeReason").value("FORCED"))

        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE session_id = ? AND kind = 'REFUND'",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )
        assertEquals(
            500L,
            jdbc.queryForObject(
                "SELECT (after_summary ->> 'uncollectedBalanceMinor')::BIGINT FROM audit_event WHERE action = 'SESSION_FORCE_CLOSED' AND target_id = ?",
                Long::class.javaObjectType,
                UUID.fromString(fixture.sessionId),
            ),
        )
    }

    @Test
    fun `forced close enforces permission tenant and serializes concurrent keys`() {
        val fixture = createBillableSession()
        val orderId = jdbc.queryForObject(
            "SELECT id FROM customer_order WHERE session_id = ?",
            UUID::class.java,
            UUID.fromString(fixture.sessionId),
        )!!
        jdbc.update("UPDATE customer_order SET status = 'IN_PREPARATION' WHERE id = ?", orderId)
        jdbc.update(
            "UPDATE order_item SET status = 'IN_PREPARATION', started_at = CURRENT_TIMESTAMP WHERE order_id = ?",
            orderId,
        )
        mockMvc.perform(
            post("/sessions/current/payments")
                .header("Authorization", "Bearer ${fixture.token}")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amountMinor":500,"tipMinor":0,"method":"SIMULATED_ONLINE"}"""),
        ).andExpect(status().isCreated)
        val route = "/staff/sessions/${fixture.sessionId}/force-close"
        val key = UUID.randomUUID().toString()
        val body = """{"reason":"Duplicate request"}"""
        mockMvc.perform(
            post(route).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isUnauthorized)

        val waiterToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        mockMvc.perform(
            post(route).header("Authorization", "Bearer $waiterToken")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isForbidden)

        jdbc.update("UPDATE user_role SET role_code = 'OWNER' WHERE user_id = ?", USER_ID)
        val ownerToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        jdbc.update(
            """
            INSERT INTO restaurant (id, name, timezone, currency)
            VALUES (?, 'Foreign tenant', 'Europe/Zurich', 'CHF')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            OTHER_RESTAURANT_ID,
        )
        jdbc.update(
            "INSERT INTO restaurant_settings (restaurant_id) VALUES (?) ON CONFLICT (restaurant_id) DO NOTHING",
            OTHER_RESTAURANT_ID,
        )
        val foreign = createGuestSession(OTHER_RESTAURANT_ID)
        mockMvc.perform(
            post("/staff/sessions/${foreign.sessionId}/force-close")
                .header("Authorization", "Bearer $ownerToken")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isNotFound)
        assertSessionRemainsOpen(foreign.sessionId)

        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val concurrentKeys = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        try {
            val attempts = concurrentKeys.map { concurrentKey ->
                executor.submit<Int> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    mockMvc.perform(
                        post(route)
                            .header("Authorization", "Bearer $ownerToken")
                            .header("Idempotency-Key", concurrentKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body),
                    ).andReturn().response.status
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(listOf(200, 200), attempts.map { it.get(20, TimeUnit.SECONDS) }.sorted())
        } finally {
            executor.shutdownNow()
        }
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'SESSION_FORCE_CLOSED' AND target_id = ?",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE session_id = ? AND kind = 'REFUND'",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )
        assertEquals(
            500L,
            jdbc.queryForObject(
                "SELECT SUM(amount_minor) FROM payment WHERE session_id = ? AND kind = 'REFUND'",
                Long::class.javaObjectType,
                UUID.fromString(fixture.sessionId),
            ),
        )
    }

    @Test
    fun `guest bill derives charges from accepted snapshots and isolates the session`() {
        val fixture = createGuestSession()
        val otherSession = createGuestSession()
        val accepted = createOrder(fixture)
        addOrderItem(accepted, fixture)
        jdbc.update("UPDATE customer_order SET status = 'ACCEPTED' WHERE id = ?", UUID.fromString(accepted))

        val cancelled = createOrder(fixture)
        addOrderItem(cancelled, fixture)
        jdbc.update("UPDATE customer_order SET status = 'ACCEPTED' WHERE id = ?", UUID.fromString(cancelled))
        jdbc.update(
            "UPDATE order_item SET status = 'CANCELLED' WHERE order_id = ?",
            UUID.fromString(cancelled),
        )

        createOrder(fixture)
        val foreign = createOrder(otherSession)
        addOrderItem(foreign, otherSession)
        jdbc.update("UPDATE customer_order SET status = 'ACCEPTED' WHERE id = ?", UUID.fromString(foreign))

        jdbc.update(
            """
            INSERT INTO restaurant (id, name, timezone, currency)
            VALUES (?, 'Foreign tenant', 'Europe/Zurich', 'CHF')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            OTHER_RESTAURANT_ID,
        )
        jdbc.update(
            "INSERT INTO restaurant_settings (restaurant_id) VALUES (?) ON CONFLICT (restaurant_id) DO NOTHING",
            OTHER_RESTAURANT_ID,
        )
        val foreignTenant = createGuestSession(OTHER_RESTAURANT_ID)
        createOrder(foreignTenant, OTHER_RESTAURANT_ID)

        mockMvc.perform(
            get("/sessions/current/bill")
                .header("Authorization", "Bearer ${fixture.token}"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.currency").value("ARS"))
            .andExpect(jsonPath("$.chargesMinor").value(100))
            .andExpect(jsonPath("$.adjustmentsMinor").value(0))
            .andExpect(jsonPath("$.paidMinor").value(0))
            .andExpect(jsonPath("$.balanceMinor").value(100))
            .andExpect(jsonPath("$.tipsMinor").value(0))
            .andExpect(jsonPath("$.adjustments.length()").value(0))
            .andExpect(jsonPath("$.payments.length()").value(0))
            .andExpect(jsonPath("$.lines.length()").value(1))
            .andExpect(jsonPath("$.lines[0].orderCode").value("#SEC"))
            .andExpect(jsonPath("$.lines[0].productName").value("Security test item"))
            .andExpect(jsonPath("$.pendingOrdersNotIncluded.length()").value(1))
            .andExpect(jsonPath("$.pendingOrdersNotIncluded[0].orderCode").value("#SEC"))
            .andExpect(jsonPath("$.pendingOrdersNotIncluded[0].totalMinor").value(100))

        mockMvc.perform(
            get("/sessions/current/bill")
                .header("Authorization", "Bearer ${foreignTenant.token}"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.currency").value("CHF"))
            .andExpect(jsonPath("$.chargesMinor").value(0))
            .andExpect(jsonPath("$.pendingOrdersNotIncluded.length()").value(1))
    }

    @Test
    fun `payments and staff adjustments persist and derive the same bill`() {
        val fixture = createGuestSession()
        val orderId = createOrder(fixture)
        addOrderItem(orderId, fixture)
        jdbc.update("UPDATE customer_order SET status = 'ACCEPTED' WHERE id = ?", UUID.fromString(orderId))
        jdbc.update(
            "UPDATE order_item SET line_total_minor = 500, unit_price_minor = 500 WHERE order_id = ?",
            UUID.fromString(orderId),
        )

        val rejectedKey = UUID.randomUUID().toString()
        val rejectedBody = """{"amountMinor":113,"tipMinor":10,"method":"SIMULATED_ONLINE"}"""
        mockMvc.perform(
            post("/sessions/current/payments")
                .header("Authorization", "Bearer ${fixture.token}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(rejectedBody),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"))

        val rejected = mockMvc.perform(
            post("/sessions/current/payments")
                .header("Authorization", "Bearer ${fixture.token}")
                .header("Idempotency-Key", rejectedKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(rejectedBody),
        ).andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.paymentId").isNotEmpty)
            .andReturn().response.contentAsString
        val rejectedPaymentId = json(rejected, "$.paymentId")
        assertEquals(
            "FAILED",
            jdbc.queryForObject(
                "SELECT status FROM payment WHERE id = ?",
                String::class.java,
                UUID.fromString(rejectedPaymentId),
            ),
        )
        assertEquals(
            0L,
            jdbc.queryForObject(
                "SELECT tip_minor FROM payment WHERE id = ?",
                Long::class.java,
                UUID.fromString(rejectedPaymentId),
            ),
        )

        mockMvc.perform(
            post("/sessions/current/payments")
                .header("Authorization", "Bearer ${fixture.token}")
                .header("Idempotency-Key", rejectedKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(rejectedBody),
        ).andExpect(status().isUnprocessableEntity)
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andExpect(jsonPath("$.paymentId").value(rejectedPaymentId))

        val paymentKey = UUID.randomUUID().toString()
        val paymentBody = """{"amountMinor":200,"tipMinor":10,"method":"SIMULATED_ONLINE"}"""
        mockMvc.perform(
            post("/sessions/current/payments")
                .header("Authorization", "Bearer ${fixture.token}")
                .header("Idempotency-Key", paymentKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(paymentBody),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.payment.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.bill.chargesMinor").value(500))
            .andExpect(jsonPath("$.bill.paidMinor").value(200))
            .andExpect(jsonPath("$.bill.tipsMinor").value(10))
            .andExpect(jsonPath("$.bill.balanceMinor").value(300))
        mockMvc.perform(
            post("/sessions/current/payments")
                .header("Authorization", "Bearer ${fixture.token}")
                .header("Idempotency-Key", paymentKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(paymentBody),
        ).andExpect(status().isCreated)
            .andExpect(header().string("Idempotent-Replayed", "true"))

        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val adjustmentKey = UUID.randomUUID().toString()
        val adjustmentBody = """{"kind":"DISCOUNT_PERCENT","percentBp":1000,"reasonCode":"PROMOTION"}"""
        mockMvc.perform(
            post("/staff/sessions/${fixture.sessionId}/adjustments")
                .header("Authorization", "Bearer $staffToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(adjustmentBody),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"))

        val appliedAdjustment = mockMvc.perform(
            post("/staff/sessions/${fixture.sessionId}/adjustments")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", adjustmentKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(adjustmentBody),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.kind").value("DISCOUNT_PERCENT"))
            .andExpect(jsonPath("$.amountMinor").value(50))
        val adjustmentId = json(appliedAdjustment.andReturn().response.contentAsString, "$.id")
        mockMvc.perform(
            post("/staff/sessions/${fixture.sessionId}/adjustments")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", adjustmentKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(adjustmentBody),
        ).andExpect(status().isCreated)
            .andExpect(header().string("Idempotent-Replayed", "true"))

        mockMvc.perform(
            get("/sessions/current/bill").header("Authorization", "Bearer ${fixture.token}"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.chargesMinor").value(500))
            .andExpect(jsonPath("$.adjustmentsMinor").value(50))
            .andExpect(jsonPath("$.paidMinor").value(200))
            .andExpect(jsonPath("$.tipsMinor").value(10))
            .andExpect(jsonPath("$.balanceMinor").value(250))

        val cashKey = UUID.randomUUID().toString()
        val cashBody = """{"amountMinor":250,"tipMinor":10,"payerLabel":"Caja"}"""
        mockMvc.perform(
            post("/staff/sessions/${fixture.sessionId}/payments")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", cashKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cashBody),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.payment.method").value("CASH_COUNTER"))
            .andExpect(jsonPath("$.bill.paidMinor").value(450))
            .andExpect(jsonPath("$.bill.tipsMinor").value(20))
            .andExpect(jsonPath("$.bill.balanceMinor").value(0))
        mockMvc.perform(
            post("/staff/sessions/${fixture.sessionId}/payments")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", cashKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cashBody),
        ).andExpect(status().isCreated)
            .andExpect(header().string("Idempotent-Replayed", "true"))
        mockMvc.perform(
            post("/sessions/current/payments")
                .header("Authorization", "Bearer ${fixture.token}")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amountMinor":0,"tipMinor":20,"method":"SIMULATED_ONLINE"}"""),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.payment.amountMinor").value(0))
            .andExpect(jsonPath("$.payment.tipMinor").value(20))
            .andExpect(jsonPath("$.bill.paidMinor").value(450))
            .andExpect(jsonPath("$.bill.tipsMinor").value(40))
            .andExpect(jsonPath("$.bill.balanceMinor").value(0))

        val voidKey = UUID.randomUUID().toString()
        val voidBody = """{"reason":"Correccion de prueba"}"""
        mockMvc.perform(
            post("/staff/adjustments/$adjustmentId/void")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", voidKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(voidBody),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("VOIDED"))
            .andExpect(jsonPath("$.voidReason").value("Correccion de prueba"))
        mockMvc.perform(
            post("/staff/adjustments/$adjustmentId/void")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", voidKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(voidBody),
        ).andExpect(status().isOk)
            .andExpect(header().string("Idempotent-Replayed", "true"))
        mockMvc.perform(
            get("/sessions/current/bill").header("Authorization", "Bearer ${fixture.token}"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.adjustmentsMinor").value(0))
            .andExpect(jsonPath("$.balanceMinor").value(50))

        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM bill_adjustment WHERE session_id = ? AND status = 'APPLIED'",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )
    }

    @Test
    fun `tip limit is enforced from gross charges and failed payments do not retain it`() {
        val fixture = createBillableSession(amountMinor = 500)
        jdbc.update(
            "UPDATE restaurant_settings SET max_tip_percent = 10 WHERE restaurant_id = ?",
            RESTAURANT_ID,
        )
        try {
            mockMvc.perform(
                post("/sessions/current/payments")
                    .header("Authorization", "Bearer ${fixture.token}")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"amountMinor":100,"tipMinor":51,"method":"SIMULATED_ONLINE"}"""),
            ).andExpect(status().isUnprocessableEntity)
                .andExpect(jsonPath("$.code").value("TIP_EXCEEDS_LIMIT"))
                .andExpect(jsonPath("$.extra.maxTipMinor").value(50))

            val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
            mockMvc.perform(
                post("/staff/sessions/${fixture.sessionId}/payments")
                    .header("Authorization", "Bearer $staffToken")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"amountMinor":100,"tipMinor":51}"""),
            ).andExpect(status().isUnprocessableEntity)
                .andExpect(jsonPath("$.code").value("TIP_EXCEEDS_LIMIT"))

            mockMvc.perform(
                post("/sessions/current/payments")
                    .header("Authorization", "Bearer ${fixture.token}")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"amountMinor":100,"tipMinor":50,"method":"SIMULATED_ONLINE"}"""),
            ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.bill.chargesMinor").value(500))
                .andExpect(jsonPath("$.bill.tipsMinor").value(50))
                .andExpect(jsonPath("$.bill.balanceMinor").value(400))

            assertEquals(
                1,
                jdbc.queryForObject(
                    "SELECT count(*) FROM payment WHERE session_id = ? AND status = 'SUCCEEDED'",
                    Int::class.java,
                    UUID.fromString(fixture.sessionId),
                ),
            )
        } finally {
            jdbc.update(
                "UPDATE restaurant_settings SET max_tip_percent = 100 WHERE restaurant_id = ?",
                RESTAURANT_ID,
            )
        }
    }

    @Test
    fun `tip only payment needs charges and an open session`() {
        val empty = createGuestSession()
        mockMvc.perform(
            post("/sessions/current/payments")
                .header("Authorization", "Bearer ${empty.token}")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amountMinor":0,"tipMinor":10,"method":"SIMULATED_ONLINE"}"""),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("TIP_NOT_ALLOWED"))

        val fixture = createBillableSession(amountMinor = 100)
        mockMvc.perform(
            post("/sessions/current/payments")
                .header("Authorization", "Bearer ${fixture.token}")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amountMinor":100,"method":"SIMULATED_ONLINE"}"""),
        ).andExpect(status().isCreated)
        jdbc.update(
            """
            UPDATE table_session
            SET status = 'CLOSED', closed_at = CURRENT_TIMESTAMP, close_reason = 'SETTLED', join_code = NULL
            WHERE id = ?
            """.trimIndent(),
            UUID.fromString(fixture.sessionId),
        )

        mockMvc.perform(
            post("/sessions/current/payments")
                .header("Authorization", "Bearer ${fixture.token}")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amountMinor":0,"tipMinor":10,"method":"SIMULATED_ONLINE"}"""),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SESSION_CLOSED"))

        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM payment WHERE session_id = ? AND tip_minor > 0",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )
    }

    @Test
    fun `staff session detail returns the same locked bill and enforces permission and tenant`() {
        val fixture = createBillableSession()
        mockMvc.perform(get("/staff/sessions/${fixture.sessionId}"))
            .andExpect(status().isUnauthorized)

        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val detail = mockMvc.perform(
            get("/staff/sessions/${fixture.sessionId}")
                .header("Authorization", "Bearer $staffToken"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.session.id").value(fixture.sessionId))
            .andExpect(jsonPath("$.session.status").value("OPEN"))
            .andExpect(jsonPath("$.session.readOnly").value(false))
            .andExpect(jsonPath("$.participants.length()").value(1))
            .andExpect(jsonPath("$.participants[0].role").value("HOST"))
            .andExpect(jsonPath("$.orders.length()").value(1))
            .andExpect(jsonPath("$.orders[0].items.length()").value(1))
            .andExpect(jsonPath("$.bill.chargesMinor").value(500))
            .andExpect(jsonPath("$.bill.balanceMinor").value(500))
            .andReturn().response.contentAsString
        assertEquals(1, JsonPath.read<List<*>>(detail, "$.bill.lines").size)

        jdbc.update("UPDATE user_role SET role_code = 'KITCHEN' WHERE user_id = ?", USER_ID)
        mockMvc.perform(
            get("/staff/sessions/${fixture.sessionId}")
                .header("Authorization", "Bearer $staffToken"),
        ).andExpect(status().isForbidden)
        jdbc.update("UPDATE user_role SET role_code = 'WAITER' WHERE user_id = ?", USER_ID)

        jdbc.update(
            """
            INSERT INTO restaurant (id, name, timezone, currency)
            VALUES (?, 'Foreign tenant', 'Europe/Zurich', 'CHF')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            OTHER_RESTAURANT_ID,
        )
        jdbc.update(
            "INSERT INTO restaurant_settings (restaurant_id) VALUES (?) ON CONFLICT (restaurant_id) DO NOTHING",
            OTHER_RESTAURANT_ID,
        )
        val foreign = createGuestSession(OTHER_RESTAURANT_ID)
        mockMvc.perform(
            get("/staff/sessions/${foreign.sessionId}")
                .header("Authorization", "Bearer $staffToken"),
        ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        mockMvc.perform(
            get("/staff/sessions/${UUID.randomUUID()}")
                .header("Authorization", "Bearer $staffToken"),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `waiter calls persist idempotently and enforce active reason cooldown and expiry`() {
        val fixture = createGuestSession()
        val key = UUID.randomUUID().toString()
        val created = mockMvc.perform(
        post("/sessions/current/waiter-calls")
            .header("Authorization", "Bearer ${fixture.token}")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"reason":"BILL","text":"La cuenta, por favor"}"""),
        ).andExpect(status().isCreated)
        .andExpect(jsonPath("$.reason").value("BILL"))
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andReturn().response.contentAsString
        val callId = json(created, "$.id")

        mockMvc.perform(
        post("/sessions/current/waiter-calls")
            .header("Authorization", "Bearer ${fixture.token}")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"reason":"BILL","text":"La cuenta, por favor"}"""),
        ).andExpect(status().isCreated)
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.id").value(callId))
        assertEquals(
        1,
        jdbc.queryForObject("SELECT count(*) FROM waiter_call WHERE id = ?", Int::class.java, UUID.fromString(callId)),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM event_log WHERE session_id = ? AND type = 'waiter.called'",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )

        mockMvc.perform(
        post("/sessions/current/waiter-calls")
            .header("Authorization", "Bearer ${fixture.token}")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"reason":"BILL"}"""),
        ).andExpect(status().isConflict)
        .andExpect(jsonPath("$.code").value("WAITER_CALL_ALREADY_ACTIVE"))

        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val staffList = mockMvc.perform(
        get("/staff/waiter-calls")
            .header("Authorization", "Bearer $staffToken"),
        ).andExpect(status().isOk)
        .andReturn().response.contentAsString
        val staffCalls = JsonPath.read<List<Map<String, Any>>>(staffList, "$.items")
        assertTrue(staffCalls.any { it["id"] == callId && it["tableLabel"] != null })

        mockMvc.perform(
        post("/staff/waiter-calls/$callId/acknowledge")
            .header("Authorization", "Bearer $staffToken"),
        ).andExpect(status().isOk)
        .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
        .andExpect(jsonPath("$.acknowledgedAt").isNotEmpty)
        mockMvc.perform(
        post("/staff/waiter-calls/$callId/resolve")
            .header("Authorization", "Bearer $staffToken"),
        ).andExpect(status().isOk)
        .andExpect(jsonPath("$.status").value("RESOLVED"))
        .andExpect(jsonPath("$.resolvedAt").isNotEmpty)
        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT count(*) FROM event_log WHERE session_id = ? AND type = 'waiter.updated'",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )
        mockMvc.perform(
        post("/staff/waiter-calls/$callId/resolve")
            .header("Authorization", "Bearer $staffToken"),
        ).andExpect(status().isConflict)
        .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
        mockMvc.perform(
        post("/sessions/current/waiter-calls")
            .header("Authorization", "Bearer ${fixture.token}")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"reason":"BILL"}"""),
        ).andExpect(status().isTooManyRequests)
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value("WAITER_CALL_COOLDOWN"))
        .andExpect(jsonPath("$.extra.retryAfterSec").isNumber)

        val expired = mockMvc.perform(
        post("/sessions/current/waiter-calls")
            .header("Authorization", "Bearer ${fixture.token}")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"reason":"WATER"}"""),
        ).andExpect(status().isCreated)
        .andReturn().response.contentAsString
        val expiredId = UUID.fromString(json(expired, "$.id"))
        jdbc.update(
        "UPDATE waiter_call SET expires_at = CURRENT_TIMESTAMP - INTERVAL '31 minutes' WHERE id = ?",
        expiredId,
        )
        val guestCalls = mockMvc.perform(
        get("/sessions/current/waiter-calls")
            .header("Authorization", "Bearer ${fixture.token}"),
        ).andExpect(status().isOk)
        .andReturn().response.contentAsString
        val guestCallRows = JsonPath.read<List<Map<String, Any>>>(guestCalls, "$.items")
        assertTrue(guestCallRows.any { it["id"] == expiredId.toString() && it["status"] == "EXPIRED" })
        assertEquals(
        "EXPIRED",
        jdbc.queryForObject("SELECT status FROM waiter_call WHERE id = ?", String::class.java, expiredId),
        )
    }

    @Test
    fun `waiter call endpoints enforce guest validity staff permission and tenant`() {
        val fixture = createGuestSession()
        mockMvc.perform(
        post("/sessions/current/waiter-calls")
            .header("Authorization", "Bearer ${fixture.token}")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"reason":"UNKNOWN"}"""),
        ).andExpect(status().isBadRequest)
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        mockMvc.perform(
        post("/sessions/current/waiter-calls")
            .header("Authorization", "Bearer ${fixture.token}")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"reason":"HELP","text":"${"x".repeat(81)}"}"""),
        ).andExpect(status().isBadRequest)
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        val call = mockMvc.perform(
        post("/sessions/current/waiter-calls")
            .header("Authorization", "Bearer ${fixture.token}")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"reason":"HELP"}"""),
        ).andExpect(status().isCreated)
        .andReturn().response.contentAsString
        val callId = json(call, "$.id")

        mockMvc.perform(
        get("/staff/waiter-calls"),
        ).andExpect(status().isUnauthorized)
        mockMvc.perform(
        post("/staff/waiter-calls/$callId/acknowledge"),
        ).andExpect(status().isUnauthorized)
        jdbc.update("UPDATE user_role SET role_code = 'KITCHEN' WHERE user_id = ?", USER_ID)
        val kitchenToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        mockMvc.perform(
        get("/staff/waiter-calls").header("Authorization", "Bearer $kitchenToken"),
        ).andExpect(status().isForbidden)
        jdbc.update("UPDATE user_role SET role_code = 'WAITER' WHERE user_id = ?", USER_ID)
        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")

        jdbc.update(
        """
        INSERT INTO restaurant (id, name, timezone, currency)
        VALUES (?, 'Foreign tenant', 'Europe/Zurich', 'CHF')
        ON CONFLICT (id) DO NOTHING
        """.trimIndent(),
        OTHER_RESTAURANT_ID,
        )
        jdbc.update(
            "INSERT INTO restaurant_settings (restaurant_id) VALUES (?) ON CONFLICT (restaurant_id) DO NOTHING",
            OTHER_RESTAURANT_ID,
        )
        val foreignFixture = createGuestSession(OTHER_RESTAURANT_ID)
        val foreignCall = mockMvc.perform(
        post("/sessions/current/waiter-calls")
            .header("Authorization", "Bearer ${foreignFixture.token}")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"reason":"HELP"}"""),
        ).andExpect(status().isCreated)
        .andReturn().response.contentAsString
        val staffCalls = mockMvc.perform(
        get("/staff/waiter-calls").header("Authorization", "Bearer $staffToken"),
        ).andExpect(status().isOk)
        .andReturn().response.contentAsString
        assertFalse(
        JsonPath.read<List<Map<String, Any>>>(staffCalls, "$.items")
            .any { it["id"] == json(foreignCall, "$.id") },
        )
        mockMvc.perform(
        post("/staff/waiter-calls/${json(foreignCall, "$.id")}/acknowledge")
            .header("Authorization", "Bearer $staffToken"),
        ).andExpect(status().isNotFound)
        .andExpect(jsonPath("$.code").value("WAITER_CALL_NOT_FOUND"))
        mockMvc.perform(
        post("/staff/waiter-calls/${UUID.randomUUID()}/acknowledge")
            .header("Authorization", "Bearer $staffToken"),
        ).andExpect(status().isNotFound)
        .andExpect(jsonPath("$.code").value("WAITER_CALL_NOT_FOUND"))

        jdbc.update(
        "UPDATE session_participant SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?",
        UUID.fromString(fixture.participantId),
        )
        mockMvc.perform(
        post("/sessions/current/waiter-calls")
            .header("Authorization", "Bearer ${fixture.token}")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"reason":"WATER"}"""),
        ).andExpect(status().isForbidden)
        .andExpect(jsonPath("$.code").value("SESSION_ACCESS_REVOKED"))
    }

    @Test
    fun `expired waiter calls persist expiry and publish update when staff action conflicts`() {
        val fixture = createGuestSession()
        val guestAuthorization = listOf("Bearer", fixture.token).joinToString(" ")
        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val staffAuthorization = listOf("Bearer", staffToken).joinToString(" ")

        val openCall = mockMvc.perform(
            post("/sessions/current/waiter-calls")
                .header("Authorization", guestAuthorization)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"WATER"}"""),
        ).andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val openCallId = UUID.fromString(json(openCall, "$.id"))
        jdbc.update(
            "UPDATE waiter_call SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?",
            openCallId,
        )

        mockMvc.perform(
            post("/staff/waiter-calls/$openCallId/acknowledge")
                .header("Authorization", staffAuthorization),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
        assertEquals(
            "EXPIRED",
            jdbc.queryForObject("SELECT status FROM waiter_call WHERE id = ?", String::class.java, openCallId),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM event_log WHERE session_id = ? AND type = 'waiter.updated'",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )

        val acknowledgedCall = mockMvc.perform(
            post("/sessions/current/waiter-calls")
                .header("Authorization", guestAuthorization)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"HELP"}"""),
        ).andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val acknowledgedCallId = UUID.fromString(json(acknowledgedCall, "$.id"))
        mockMvc.perform(
            post("/staff/waiter-calls/$acknowledgedCallId/acknowledge")
                .header("Authorization", staffAuthorization),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
        jdbc.update(
            "UPDATE waiter_call SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?",
            acknowledgedCallId,
        )

        mockMvc.perform(
            post("/staff/waiter-calls/$acknowledgedCallId/resolve")
                .header("Authorization", staffAuthorization),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
        assertEquals(
            "EXPIRED",
            jdbc.queryForObject(
                "SELECT status FROM waiter_call WHERE id = ?",
                String::class.java,
                acknowledgedCallId,
            ),
        )
        assertEquals(
            3,
            jdbc.queryForObject(
                "SELECT count(*) FROM event_log WHERE session_id = ? AND type = 'waiter.updated'",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )
    }

    @Test
    fun `concurrent waiter calls for one reason persist only one active row`() {
        val fixture = createGuestSession()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
        val requests = (1..2).map {
            Callable {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                val response = mockMvc.perform(
                    post("/sessions/current/waiter-calls")
                        .header("Authorization", "Bearer ${fixture.token}")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"reason":"WATER"}"""),
                ).andReturn().response
                response.status to response.contentAsString
            }
        }
        val futures = requests.map(pool::submit)
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        start.countDown()
        val results = futures.map { it.get(30, TimeUnit.SECONDS) }
        assertEquals(listOf(201, 409), results.map { it.first }.sorted())
        assertTrue(results.any { (status, body) ->
            status == 409 && json(body, "$.code") == "WAITER_CALL_ALREADY_ACTIVE"
        })
        assertEquals(
            1,
            jdbc.queryForObject(
                """
                SELECT count(*) FROM waiter_call
                WHERE session_id = ? AND reason = 'WATER' AND status IN ('OPEN', 'ACKNOWLEDGED')
                """.trimIndent(),
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )
        } finally {
        pool.shutdownNow()
        }
    }

    @Test
    fun `staff board can refresh after receiving a waiter call event`() {
        val fixture = createGuestSession()
        val cursor = eventCursor()
        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val response = openStaffEventStream(staffToken, cursor)
        val reader = Executors.newSingleThreadExecutor()
        try {
            val streamReader = BufferedReader(InputStreamReader(response.body(), Charsets.UTF_8))
            val message = reader.submit(Callable {
                readSseEvent(streamReader, "waiter.called")
            })
            val created = mockMvc.perform(
                post("/sessions/current/waiter-calls")
                    .header("Authorization", listOf("Bearer", fixture.token).joinToString(" "))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"reason":"BILL"}"""),
            ).andExpect(status().isCreated)
                .andReturn().response.contentAsString
            val callId = json(created, "$.id")

            assertTrue(message.get(10, TimeUnit.SECONDS).contains("event:waiter.called"))

            val tableId = jdbc.queryForObject(
                "SELECT table_id FROM table_session WHERE id = ?",
                UUID::class.java,
                UUID.fromString(fixture.sessionId),
            )!!
            val board = mockMvc.perform(
                get("/staff/tables")
                    .header("Authorization", listOf("Bearer", staffToken).joinToString(" ")),
            ).andExpect(status().isOk)
                .andReturn().response.contentAsString
            val tables = JsonPath.read<List<Map<String, Any>>>(board, "$.items")
            assertEquals(true, tables.single { it["tableId"] == tableId.toString() }["waiterCallActive"])

            val acknowledged = reader.submit(Callable { readSseEvent(streamReader, "waiter.updated") })
            mockMvc.perform(
                post("/staff/waiter-calls/$callId/acknowledge")
                    .header("Authorization", listOf("Bearer", staffToken).joinToString(" ")),
            ).andExpect(status().isOk)
            assertTrue(acknowledged.get(10, TimeUnit.SECONDS).contains("event:waiter.updated"))

            val resolved = reader.submit(Callable { readSseEvent(streamReader, "waiter.updated") })
            mockMvc.perform(
                post("/staff/waiter-calls/$callId/resolve")
                    .header("Authorization", listOf("Bearer", staffToken).joinToString(" ")),
            ).andExpect(status().isOk)
            assertTrue(resolved.get(10, TimeUnit.SECONDS).contains("event:waiter.updated"))

            val refreshedBoard = mockMvc.perform(
                get("/staff/tables")
                    .header("Authorization", listOf("Bearer", staffToken).joinToString(" ")),
            ).andExpect(status().isOk)
                .andReturn().response.contentAsString
            val refreshedTables = JsonPath.read<List<Map<String, Any>>>(refreshedBoard, "$.items")
            assertEquals(false, refreshedTables.single { it["tableId"] == tableId.toString() }["waiterCallActive"])
        } finally {
            response.body().close()
            reader.shutdownNow()
        }
    }

    @Test
    fun `staff table board derives order billing and waiter call status for its tenant`() {
        val fixture = createGuestSession()
        val sessionId = UUID.fromString(fixture.sessionId)
        val tableId = jdbc.queryForObject(
        "SELECT table_id FROM table_session WHERE id = ?",
        UUID::class.java,
        sessionId,
        )!!
        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        fun tableRow(): Map<String, Any> {
        val response = mockMvc.perform(
            get("/staff/tables").header("Authorization", "Bearer $staffToken"),
        ).andExpect(status().isOk)
            .andReturn().response.contentAsString
        val rows = JsonPath.read<List<Map<String, Any>>>(response, "$.items")
        return rows.single { it["tableId"] == tableId.toString() }
        }

        assertEquals("READY_TO_CLOSE", tableRow()["displayStatus"])
        val call = mockMvc.perform(
        post("/sessions/current/waiter-calls")
            .header("Authorization", "Bearer ${fixture.token}")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"reason":"BILL"}"""),
        ).andExpect(status().isCreated)
        .andReturn().response.contentAsString
        assertEquals(true, tableRow()["waiterCallActive"])

        val orderId = UUID.fromString(createOrder(fixture))
        addOrderItem(orderId.toString(), fixture)
        assertEquals("PENDING_ORDERS", tableRow()["displayStatus"])
        jdbc.update("UPDATE customer_order SET status = 'ACCEPTED' WHERE id = ?", orderId)
        jdbc.update("UPDATE order_item SET status = 'IN_PREPARATION' WHERE order_id = ?", orderId)
        assertEquals("IN_SERVICE", tableRow()["displayStatus"])
        jdbc.update("UPDATE order_item SET status = 'DELIVERED' WHERE order_id = ?", orderId)
        assertEquals("AWAITING_PAYMENT", tableRow()["displayStatus"])

        mockMvc.perform(
        post("/sessions/current/payments")
            .header("Authorization", "Bearer ${fixture.token}")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"amountMinor":100,"method":"SIMULATED_ONLINE"}"""),
        ).andExpect(status().isCreated)
        val paid = tableRow()
        assertEquals("READY_TO_CLOSE", paid["displayStatus"])
        assertEquals(0, (paid["balanceMinor"] as Number).toLong())
        assertEquals(sessionId.toString(), paid["sessionId"])

        mockMvc.perform(get("/staff/tables"))
        .andExpect(status().isUnauthorized)
    }

    @Test
    fun `refund limit uses net principal and does not refund tips`() {
        val fixture = createOverpaidSession(amountMinor = 200, tipMinor = 20)
        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")

        mockMvc.perform(
            post("/staff/sessions/${fixture.sessionId}/refunds")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amountMinor":201,"reason":"Overpayment correction"}"""),
        ).andExpect(status().isConflict)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_PAID"))
            .andExpect(jsonPath("$.extra.maxRefundMinor").value(200))

        mockMvc.perform(
            post("/staff/sessions/${fixture.sessionId}/refunds")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amountMinor":200,"reason":"Overpayment correction"}"""),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.payment.kind").value("REFUND"))
            .andExpect(jsonPath("$.payment.amountMinor").value(200))
            .andExpect(jsonPath("$.payment.tipMinor").value(0))
            .andExpect(jsonPath("$.bill.paidMinor").value(0))
            .andExpect(jsonPath("$.bill.tipsMinor").value(20))
            .andExpect(jsonPath("$.bill.balanceMinor").value(0))
    }

    @Test
    fun `refund requires staff permission and cannot cross restaurant tenant`() {
        val fixture = createOverpaidSession()
        val body = """{"amountMinor":200,"reason":"Overpayment correction"}"""
        mockMvc.perform(
            post("/staff/sessions/${fixture.sessionId}/refunds")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isUnauthorized)

        jdbc.update("UPDATE user_role SET role_code = 'KITCHEN' WHERE user_id = ?", USER_ID)
        val kitchenToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        mockMvc.perform(
            post("/staff/sessions/${fixture.sessionId}/refunds")
                .header("Authorization", "Bearer $kitchenToken")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isForbidden)

        jdbc.update("UPDATE user_role SET role_code = 'WAITER' WHERE user_id = ?", USER_ID)
        jdbc.update(
            """
            INSERT INTO restaurant (id, name, timezone, currency)
            VALUES (?, 'Foreign tenant', 'Europe/Zurich', 'CHF')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            OTHER_RESTAURANT_ID,
        )
        jdbc.update(
            "INSERT INTO restaurant_settings (restaurant_id) VALUES (?) ON CONFLICT (restaurant_id) DO NOTHING",
            OTHER_RESTAURANT_ID,
        )
        val foreignFixture = createGuestSession(OTHER_RESTAURANT_ID)
        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        mockMvc.perform(
            post("/staff/sessions/${foreignFixture.sessionId}/refunds")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM payment WHERE session_id = ? AND kind = 'REFUND'",
                Int::class.java,
                UUID.fromString(foreignFixture.sessionId),
            ),
        )
    }

    @Test
    fun `refund retry with the same key returns one persisted movement`() {
        val fixture = createOverpaidSession()
        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val key = UUID.randomUUID().toString()
        val body = """{"amountMinor":200,"reason":"Overpayment correction"}"""

        mockMvc.perform(
            post("/staff/sessions/${fixture.sessionId}/refunds")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.payment.kind").value("REFUND"))

        mockMvc.perform(
            post("/staff/sessions/${fixture.sessionId}/refunds")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isCreated)
            .andExpect(header().string("Idempotent-Replayed", "true"))

        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM payment WHERE session_id = ? AND kind = 'REFUND'",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                """
                SELECT count(*) FROM audit_event
                WHERE action = 'REFUND_CREATED'
                  AND target_id = (SELECT id FROM payment WHERE session_id = ? AND kind = 'REFUND')
                """.trimIndent(),
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )
    }

    @Test
    fun `concurrent refunds with distinct keys serialize against net paid`() {
        val fixture = createOverpaidSession()
        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = (1..2).map {
                executor.submit<Pair<Int, String>> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    val response = mockMvc.perform(
                        post("/staff/sessions/${fixture.sessionId}/refunds")
                            .header("Authorization", "Bearer $staffToken")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"amountMinor":200,"reason":"Overpayment correction"}"""),
                    ).andReturn().response
                    response.status to response.contentAsString
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val responses = attempts.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(listOf(201, 409), responses.map { it.first }.sorted())
            val conflict = responses.single { it.first == 409 }.second
            assertEquals("REFUND_EXCEEDS_PAID", JsonPath.read<String>(conflict, "$.code"))
            assertEquals(0, JsonPath.read<Int>(conflict, "$.extra.maxRefundMinor"))
        } finally {
            executor.shutdownNow()
        }
        assertEquals(
            200L,
            jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM payment WHERE session_id = ? AND kind = 'REFUND'",
                Long::class.javaObjectType,
                UUID.fromString(fixture.sessionId),
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'REFUND_CREATED' AND target_id IN (SELECT id FROM payment WHERE session_id = ? AND kind = 'REFUND')",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )
    }

    @Test
    fun `void rejects a failed payment without changing its state`() {
        val fixture = createBillableSession()
        val failed = mockMvc.perform(
            post("/sessions/current/payments")
                .header("Authorization", "Bearer ${fixture.token}")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amountMinor":113,"method":"SIMULATED_ONLINE"}"""),
        ).andExpect(status().isUnprocessableEntity)
            .andReturn().response.contentAsString
        val paymentId = json(failed, "$.paymentId")
        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")

        mockMvc.perform(
            post("/staff/payments/$paymentId/void")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"Invalid payment"}"""),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("PAYMENT_NOT_VOIDABLE"))

        assertEquals(
            "FAILED",
            jdbc.queryForObject(
                "SELECT status FROM payment WHERE id = ?",
                String::class.java,
                UUID.fromString(paymentId),
            ),
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'PAYMENT_VOIDED' AND target_id = ?",
                Int::class.java,
                UUID.fromString(paymentId),
            ),
        )
    }

    @Test
    fun `successful payment void is idempotent and cannot be repeated with a new key`() {
        val fixture = createBillableSession()
        val created = mockMvc.perform(
            post("/sessions/current/payments")
                .header("Authorization", "Bearer ${fixture.token}")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amountMinor":200,"tipMinor":20,"method":"SIMULATED_ONLINE"}"""),
        ).andExpect(status().isCreated)
            .andReturn().response.contentAsString
        assertEquals(20, JsonPath.read<Int>(created, "$.bill.tipsMinor"))
        val paymentId = json(created, "$.payment.id")
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM event_log WHERE session_id = ? AND type = 'bill.updated'",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )
        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val voidKey = UUID.randomUUID().toString()
        val body = """{"reason":"Duplicate collection"}"""

        mockMvc.perform(
            post("/staff/payments/$paymentId/void")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", voidKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.payment.status").value("VOIDED"))
            .andExpect(jsonPath("$.bill.paidMinor").value(0))
            .andExpect(jsonPath("$.bill.tipsMinor").value(0))
        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT count(*) FROM event_log WHERE session_id = ? AND type = 'bill.updated'",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )

        mockMvc.perform(
            post("/staff/payments/$paymentId/void")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", voidKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isOk)
            .andExpect(header().string("Idempotent-Replayed", "true"))
        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT count(*) FROM event_log WHERE session_id = ? AND type = 'bill.updated'",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )

        mockMvc.perform(
            post("/staff/payments/$paymentId/void")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("PAYMENT_NOT_VOIDABLE"))

        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'PAYMENT_VOIDED' AND target_id = ?",
                Int::class.java,
                UUID.fromString(paymentId),
            ),
        )
    }

    @Test
    fun `adjustments enforce staff permission and tenant isolation`() {
        val fixture = createBillableSession()
        val body = """{"kind":"DISCOUNT_AMOUNT","amountMinor":100,"reasonCode":"PROMOTION"}"""
        mockMvc.perform(
            post("/staff/sessions/${fixture.sessionId}/adjustments")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isUnauthorized)

        jdbc.update("UPDATE user_role SET role_code = 'KITCHEN' WHERE user_id = ?", USER_ID)
        val kitchenToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        mockMvc.perform(
            post("/staff/sessions/${fixture.sessionId}/adjustments")
                .header("Authorization", "Bearer $kitchenToken")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isForbidden)

        jdbc.update("UPDATE user_role SET role_code = 'WAITER' WHERE user_id = ?", USER_ID)
        jdbc.update(
            """
            INSERT INTO restaurant (id, name, timezone, currency)
            VALUES (?, 'Foreign tenant', 'Europe/Zurich', 'CHF')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            OTHER_RESTAURANT_ID,
        )
        jdbc.update(
            "INSERT INTO restaurant_settings (restaurant_id) VALUES (?) ON CONFLICT (restaurant_id) DO NOTHING",
            OTHER_RESTAURANT_ID,
        )
        val foreignFixture = createGuestSession(OTHER_RESTAURANT_ID)
        val waiterToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        mockMvc.perform(
            post("/staff/sessions/${foreignFixture.sessionId}/adjustments")
                .header("Authorization", "Bearer $waiterToken")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM bill_adjustment WHERE session_id = ?",
                Int::class.java,
                UUID.fromString(foreignFixture.sessionId),
            ),
        )
    }

    @Test
    fun `concurrent staff adjustments enforce the accumulated limit`() {
        val fixture = createBillableSession(amountMinor = 1000)
        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = (1..2).map {
                executor.submit<Pair<Int, String>> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    val response = mockMvc.perform(
                        post("/staff/sessions/${fixture.sessionId}/adjustments")
                            .header("Authorization", "Bearer $staffToken")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"kind":"DISCOUNT_AMOUNT","amountMinor":100,"reasonCode":"PROMOTION"}"""),
                    ).andReturn().response
                    response.status to response.contentAsString
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val responses = attempts.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(listOf(201, 403), responses.map { it.first }.sorted())
            val rejected = responses.single { it.first == 403 }.second
            assertEquals("ADJUSTMENT_LIMIT_EXCEEDED", JsonPath.read<String>(rejected, "$.code"))
        } finally {
            executor.shutdownNow()
        }

        // El lock de sesión hace que el límite acumulado se recalcule tras la primera escritura.
        assertEquals(
            100L,
            jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM bill_adjustment WHERE session_id = ? AND status = 'APPLIED'",
                Long::class.javaObjectType,
                UUID.fromString(fixture.sessionId),
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'ADJUSTMENT_APPLIED' AND target_id IN (SELECT id FROM bill_adjustment WHERE session_id = ?)",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )
    }

    @Test
    fun `cancelling a courtesy item voids its adjustment in the same session transaction`() {
        val fixture = createGuestSession()
        val orderId = createOrder(fixture)
        addOrderItem(orderId, fixture)
        val itemId = jdbc.queryForObject(
            "SELECT id FROM order_item WHERE order_id = ?",
            UUID::class.java,
            UUID.fromString(orderId),
        )
        jdbc.update(
            "UPDATE customer_order SET status = 'IN_PREPARATION', accepted_at = CURRENT_TIMESTAMP, decided_at = CURRENT_TIMESTAMP WHERE id = ?",
            UUID.fromString(orderId),
        )
        jdbc.update(
            "UPDATE order_item SET status = 'IN_PREPARATION', queued_at = CURRENT_TIMESTAMP, started_at = CURRENT_TIMESTAMP WHERE id = ?",
            itemId,
        )
        jdbc.update("UPDATE user_role SET role_code = 'OWNER' WHERE user_id = ?", USER_ID)
        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")

        mockMvc.perform(
            post("/staff/sessions/${fixture.sessionId}/adjustments")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"kind":"COURTESY_ITEM","orderItemId":"$itemId","reasonCode":"COURTESY_HOUSE"}"""),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.kind").value("COURTESY_ITEM"))
            .andExpect(jsonPath("$.amountMinor").value(100))

        mockMvc.perform(
            post("/staff/order-items/$itemId/cancel")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"Item damaged"}"""),
        ).andExpect(status().isOk)

        assertEquals(
            "VOIDED",
            jdbc.queryForObject(
                "SELECT status FROM bill_adjustment WHERE order_item_id = ?",
                String::class.java,
                itemId,
            ),
        )
        mockMvc.perform(
            get("/sessions/current/bill").header("Authorization", "Bearer ${fixture.token}"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.chargesMinor").value(0))
            .andExpect(jsonPath("$.adjustmentsMinor").value(0))
            .andExpect(jsonPath("$.balanceMinor").value(0))
    }

    @Test
    fun `simultaneous payment retries create only one movement`() {
        val fixture = createGuestSession()
        val orderId = createOrder(fixture)
        addOrderItem(orderId, fixture)
        jdbc.update("UPDATE customer_order SET status = 'ACCEPTED' WHERE id = ?", UUID.fromString(orderId))
        jdbc.update(
            "UPDATE order_item SET line_total_minor = 500, unit_price_minor = 500 WHERE order_id = ?",
            UUID.fromString(orderId),
        )
        val key = UUID.randomUUID().toString()
        val body = """{"amountMinor":200,"method":"SIMULATED_ONLINE"}"""
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = (1..2).map {
                executor.submit<Int> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    mockMvc.perform(
                        post("/sessions/current/payments")
                            .header("Authorization", "Bearer ${fixture.token}")
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body),
                    ).andReturn().response.status
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(listOf(201, 201), attempts.map { it.get(15, TimeUnit.SECONDS) }.sorted())
        } finally {
            executor.shutdownNow()
        }
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM payment WHERE session_id = ? AND idempotency_key = ?",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
                key,
            ),
        )
    }

    @Test
    fun `guest bill requires a valid participant token`() {
        mockMvc.perform(get("/sessions/current/bill"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
    }

    @Test
    fun `guest bill rejects revoked participant access`() {
        val fixture = createGuestSession()
        jdbc.update(
            "UPDATE session_participant SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?",
            UUID.fromString(fixture.participantId),
        )

        mockMvc.perform(
            get("/sessions/current/bill")
                .header("Authorization", "Bearer ${fixture.token}"),
        ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("SESSION_ACCESS_REVOKED"))
    }

    @Test
    fun `guest bill is readable for 24 hours after session closure`() {
        val fixture = createGuestSession()
        val orderId = createOrder(fixture)
        addOrderItem(orderId, fixture)
        jdbc.update("UPDATE customer_order SET status = 'ACCEPTED' WHERE id = ?", UUID.fromString(orderId))
        jdbc.update(
            """
            UPDATE table_session
            SET status = 'CLOSED', closed_at = CURRENT_TIMESTAMP - INTERVAL '1 hour'
            WHERE id = ?
            """.trimIndent(),
            UUID.fromString(fixture.sessionId),
        )

        mockMvc.perform(
            get("/sessions/current/bill").header("Authorization", "Bearer ${fixture.token}"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.balanceMinor").value(100))

        jdbc.update(
            "UPDATE table_session SET closed_at = CURRENT_TIMESTAMP - INTERVAL '25 hours' WHERE id = ?",
            UUID.fromString(fixture.sessionId),
        )
        mockMvc.perform(
            get("/sessions/current/bill").header("Authorization", "Bearer ${fixture.token}"),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SESSION_CLOSED"))
    }

    @Test
    fun `receipt requires an authorized closed guest session`() {
        val fixture = createGuestSession()
        mockMvc.perform(get("/sessions/current/receipt.pdf"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))

        val authorization = "Bearer ${fixture.token}"
        mockMvc.perform(get("/sessions/current/receipt.pdf").header("Authorization", authorization))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("RECEIPT_NOT_AVAILABLE"))

        jdbc.update(
            "UPDATE session_participant SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?",
            UUID.fromString(fixture.participantId),
        )
        mockMvc.perform(get("/sessions/current/receipt.pdf").header("Authorization", authorization))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("SESSION_ACCESS_REVOKED"))
    }

    @Test
    fun `receipt renders historical billing snapshots and forced-close balance`() {
        val fixture = createBillableSession()
        val sessionId = UUID.fromString(fixture.sessionId)
        val authorization = "Bearer ${fixture.token}"
        val orderItemId = jdbc.queryForObject(
            "SELECT id FROM order_item WHERE session_id = ?",
            UUID::class.java,
            sessionId,
        )!!
        jdbc.update(
            """
            UPDATE order_item
            SET product_name = ?, base_price_minor = 420, options_delta_minor = 80,
                unit_price_minor = 500, line_total_minor = 500,
                options_snapshot = ?::jsonb
            WHERE id = ?
            """.trimIndent(),
            "W".repeat(150),
            """[{"groupId":"group","optionId":"option","groupName":"Tamaño","optionName":"Grande","priceDeltaMinor":80}]""",
            orderItemId,
        )
        mockMvc.perform(
            post("/sessions/current/payments")
                .header("Authorization", authorization)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amountMinor":200,"tipMinor":25,"method":"SIMULATED_ONLINE"}"""),
        ).andExpect(status().isCreated)
        val voidedTipPayment = mockMvc.perform(
            post("/sessions/current/payments")
                .header("Authorization", authorization)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amountMinor":100,"tipMinor":37,"method":"SIMULATED_ONLINE"}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val voidedTipPaymentId = json(voidedTipPayment, "$.payment.id")
        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        mockMvc.perform(
            post("/staff/payments/$voidedTipPaymentId/void")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"Duplicate collection"}"""),
        ).andExpect(status().isOk)
        jdbc.update(
            """
            INSERT INTO bill_adjustment
                (id, restaurant_id, session_id, kind, status, amount_minor, reason_code, created_by, idempotency_key)
            VALUES (?, ?, ?, 'MANUAL_ADJUSTMENT', 'APPLIED', 50, 'TEST', ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            RESTAURANT_ID,
            sessionId,
            USER_ID,
            UUID.randomUUID().toString(),
        )
        jdbc.update(
            """
            UPDATE table_session
            SET status = 'CLOSED', closed_at = CURRENT_TIMESTAMP, close_reason = 'FORCED', join_code = NULL
            WHERE id = ?
            """.trimIndent(),
            sessionId,
        )

        val response = mockMvc.perform(
            get("/sessions/current/receipt.pdf").header("Authorization", authorization),
        ).andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().response.contentAsByteArray

        assertTrue(response.take(4).toByteArray().contentEquals("%PDF".toByteArray()))
        val text = Loader.loadPDF(response).use { PDFTextStripper().getText(it) }
        assertTrue(text.contains("Somphie Zúrich"))
        assertTrue(text.contains("Comprobante no fiscal — no válido como factura"))
        assertTrue(text.contains(fixture.sessionId))
        assertTrue(text.contains("Security ${fixture.tableCode}"))
        assertTrue(text.filterNot(Char::isWhitespace).contains("W".repeat(150)))
        assertTrue(text.lines().none { it.length > 100 })
        assertTrue(text.contains("Tamaño: Grande"))
        assertTrue(text.filterNot(Char::isWhitespace).contains("baseARS4.20"))
        assertTrue(text.contains("Descuento: ARS 0.50"))
        assertTrue(text.contains("ARS 5.00"))
        assertTrue(text.contains("ARS 2.00"))
        assertTrue(text.contains("Ajustes aplicados: ARS 0.50"))
        assertTrue(text.contains("Propinas (separadas del saldo): ARS 0.25"))
        assertFalse(text.contains("Propina: ARS 0.37"))
        assertTrue(text.contains("Propina"))
        assertTrue(text.contains("ARS 0.25"))
        assertTrue(text.contains("Cierre forzado"))
        assertTrue(text.contains("Saldo no cobrado: ARS 2.50"))

        jdbc.update(
            "UPDATE table_session SET closed_at = CURRENT_TIMESTAMP - INTERVAL '25 hours' WHERE id = ?",
            sessionId,
        )
        mockMvc.perform(get("/sessions/current/receipt.pdf").header("Authorization", authorization))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SESSION_CLOSED"))
    }

    @Test
    fun `staff opens a session without host and idempotent replay returns same code`() {
        val tableId = UUID.randomUUID()
        val tableCode = "STAFF-${UUID.randomUUID()}"
        val tableLabel = "Staff $tableCode"
        jdbc.update(
            "INSERT INTO dining_table (id, restaurant_id, label, table_code) VALUES (?, ?, ?, ?)",
            tableId,
            RESTAURANT_ID,
            tableLabel,
            tableCode,
        )
        val cursorBeforeOpen = eventCursor()
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val key = UUID.randomUUID().toString()

        val first = mockMvc.perform(
            post("/staff/tables/$tableId/sessions")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", key),
        ).andExpect(status().isCreated)
            .andExpect(header().doesNotExist("Idempotent-Replayed"))
            .andExpect(jsonPath("$.session.status").value("OPEN"))
            .andExpect(jsonPath("$.session.tableLabel").value(tableLabel))
            .andExpect(jsonPath("$.session.readOnly").value(false))
            .andExpect(jsonPath("$.joinCode").isNotEmpty)
            .andReturn().response.contentAsString
        val sessionId = json(first, "$.session.id")
        val joinCode = json(first, "$.joinCode")

        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_participant WHERE session_id = ?",
                Int::class.java,
                UUID.fromString(sessionId),
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'SESSION_OPENED' AND target_id = ?",
                Int::class.java,
                UUID.fromString(sessionId),
            ),
        )
        assertEquals(
            USER_ID.toString(),
            jdbc.queryForObject(
                "SELECT opened_by_user_id::text FROM table_session WHERE id = ?",
                String::class.java,
                UUID.fromString(sessionId),
            ),
        )

        mockMvc.perform(
            post("/staff/tables/$tableId/sessions")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", key),
        ).andExpect(status().isCreated)
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andExpect(jsonPath("$.session.id").value(sessionId))
            .andExpect(jsonPath("$.joinCode").value(joinCode))
        assertEquals(
            1,
            jdbc.queryForObject(
                """
                SELECT count(*) FROM event_log
                WHERE restaurant_id = ? AND id > ? AND type = 'session.updated' AND aggregate_id = ?
                """.trimIndent(),
                Int::class.java,
                RESTAURANT_ID,
                cursorBeforeOpen,
                UUID.fromString(sessionId),
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                """
                SELECT count(*) FROM event_log
                WHERE restaurant_id = ? AND id > ? AND type = 'table.updated' AND aggregate_id = ?
                """.trimIndent(),
                Int::class.java,
                RESTAURANT_ID,
                cursorBeforeOpen,
                tableId,
            ),
        )
    }

    @Test
    fun `manual session opening requires the explicit permission`() {
        jdbc.update("DELETE FROM user_role WHERE user_id = ?", USER_ID)
        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'KITCHEN')", USER_ID)
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val tableId = createStaffTable(RESTAURANT_ID)

        mockMvc.perform(
            post("/staff/tables/$tableId/sessions")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
        ).andExpect(status().isForbidden)
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM table_session WHERE table_id = ?",
                Int::class.java,
                tableId,
            ),
        )
    }

    @Test
    fun `manual session opening validates its idempotency key`() {
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val tableId = createStaffTable(RESTAURANT_ID)

        mockMvc.perform(
            post("/staff/tables/$tableId/sessions")
                .header("Authorization", "Bearer $token"),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"))

        mockMvc.perform(
            post("/staff/tables/$tableId/sessions")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", "not-a-uuid"),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `staff rotates join code and replays the same result`() {
        val tableId = createStaffTable(RESTAURANT_ID)
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val opened = mockMvc.perform(
            post("/staff/tables/$tableId/sessions")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
        ).andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val sessionId = json(opened, "$.session.id")
        val oldCode = json(opened, "$.joinCode")
        val key = UUID.randomUUID().toString()

        val rotated = mockMvc.perform(
            post("/staff/sessions/$sessionId/join-code/rotate")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", key),
        ).andExpect(status().isOk)
            .andExpect(header().doesNotExist("Idempotent-Replayed"))
            .andReturn().response.contentAsString
        val newCode = json(rotated, "$.joinCode")
        assertFalse(oldCode == newCode)
        assertEquals(
            newCode,
            jdbc.queryForObject(
                "SELECT join_code FROM table_session WHERE id = ?",
                String::class.java,
                UUID.fromString(sessionId),
            ),
        )

        mockMvc.perform(
            post("/staff/sessions/$sessionId/join-code/rotate")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", key),
        ).andExpect(status().isOk)
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andExpect(jsonPath("$.joinCode").value(newCode))

        jdbc.update(
            """
            UPDATE table_session
            SET status = 'CLOSED', closed_at = CURRENT_TIMESTAMP, close_reason = 'EMPTY', join_code = NULL
            WHERE id = ?
            """.trimIndent(),
            UUID.fromString(sessionId),
        )
        mockMvc.perform(
            post("/staff/sessions/$sessionId/join-code/rotate")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SESSION_CLOSED"))

        jdbc.update("DELETE FROM user_role WHERE user_id = ?", USER_ID)
        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'KITCHEN')", USER_ID)
        val kitchenToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        mockMvc.perform(
            post("/staff/sessions/$sessionId/join-code/rotate")
                .header("Authorization", "Bearer $kitchenToken")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `staff revokes a participant once and records activity and mapped audit actor`() {
        val fixture = createGuestSession()
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val route = "/staff/sessions/${fixture.sessionId}/participants/${fixture.participantId}/revoke"

        repeat(2) {
            mockMvc.perform(
                post(route).header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(fixture.participantId))
                .andExpect(jsonPath("$.revoked").value(true))
        }

        val revokedAt = jdbc.queryForObject(
            "SELECT revoked_at FROM session_participant WHERE id = ?",
            java.sql.Timestamp::class.java,
            UUID.fromString(fixture.participantId),
        )
        assertEquals(
            revokedAt,
            jdbc.queryForObject(
                "SELECT last_activity_at FROM table_session WHERE id = ?",
                java.sql.Timestamp::class.java,
                UUID.fromString(fixture.sessionId),
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'PARTICIPANT_REVOKED' AND target_id = ? AND actor_type = 'STAFF' AND actor_id = ?",
                Int::class.java,
                UUID.fromString(fixture.participantId),
                USER_ID,
            ),
        )
    }

    @Test
    fun `host can revoke members but not themselves and only the host is authorized`() {
        val fixture = createGuestSession()
        val member = createMemberFixture(fixture)
        val memberToRevoke = createMemberFixture(fixture)

        mockMvc.perform(
            delete("/sessions/current/participants/${memberToRevoke.first}")
                .header("Authorization", "Bearer ${fixture.token}"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(memberToRevoke.first.toString()))
            .andExpect(jsonPath("$.revoked").value(true))
        mockMvc.perform(
            get("/sessions/current/orders").header("Authorization", "Bearer ${memberToRevoke.second}"),
        ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("SESSION_ACCESS_REVOKED"))

        mockMvc.perform(
            delete("/sessions/current/participants/${fixture.participantId}")
                .header("Authorization", "Bearer ${fixture.token}"),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("HOST_CANNOT_BE_REVOKED"))

        mockMvc.perform(
            delete("/sessions/current/participants/${memberToRevoke.first}")
                .header("Authorization", "Bearer ${member.second}"),
        ).andExpect(status().isForbidden)

        mockMvc.perform(delete("/sessions/current/participants/${member.first}"))
            .andExpect(status().isUnauthorized)

        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'PARTICIPANT_REVOKED' AND target_id = ? AND actor_type = 'CUSTOMER' AND actor_id = ?",
                Int::class.java,
                memberToRevoke.first,
                UUID.fromString(fixture.participantId),
            ),
        )
    }

    @Test
    fun `staff revocation enforces tenant permission and open session state`() {
        val foreign = createGuestSession(OTHER_RESTAURANT_ID)
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        mockMvc.perform(
            post("/staff/sessions/${foreign.sessionId}/participants/${foreign.participantId}/revoke")
                .header("Authorization", "Bearer $token"),
        ).andExpect(status().isNotFound)
        assertEquals(
            null,
            jdbc.queryForObject(
                "SELECT revoked_at FROM session_participant WHERE id = ?",
                java.sql.Timestamp::class.java,
                UUID.fromString(foreign.participantId),
            ),
        )

        val fixture = createGuestSession()
        jdbc.update(
            "UPDATE table_session SET status = 'CLOSED', closed_at = CURRENT_TIMESTAMP, close_reason = 'EMPTY', join_code = NULL WHERE id = ?",
            UUID.fromString(fixture.sessionId),
        )
        mockMvc.perform(
            post("/staff/sessions/${fixture.sessionId}/participants/${fixture.participantId}/revoke")
                .header("Authorization", "Bearer $token"),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SESSION_CLOSED"))
    }

    @Test
    fun `staff revocation requires its explicit permission`() {
        val fixture = createGuestSession()
        jdbc.update("DELETE FROM user_role WHERE user_id = ?", USER_ID)
        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'KITCHEN')", USER_ID)
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")

        mockMvc.perform(
            post("/staff/sessions/${fixture.sessionId}/participants/${fixture.participantId}/revoke")
                .header("Authorization", "Bearer $token"),
        ).andExpect(status().isForbidden)
        assertEquals(
            null,
            jdbc.queryForObject(
                "SELECT revoked_at FROM session_participant WHERE id = ?",
                java.sql.Timestamp::class.java,
                UUID.fromString(fixture.participantId),
            ),
        )
    }

    @Test
    fun `concurrent staff revocations serialize and audit the transition once`() {
        val fixture = createGuestSession()
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val requests = (1..2).map {
                executor.submit(Callable {
                    start.await()
                    mockMvc.perform(
                        post("/staff/sessions/${fixture.sessionId}/participants/${fixture.participantId}/revoke")
                            .header("Authorization", "Bearer $token"),
                    ).andReturn().response
                })
            }
            start.countDown()
            assertEquals(listOf(200, 200), requests.map { it.get(20, TimeUnit.SECONDS).status }.sorted())
            assertEquals(
                1,
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_event WHERE action = 'PARTICIPANT_REVOKED' AND target_id = ?",
                    Int::class.java,
                    UUID.fromString(fixture.participantId),
                ),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `manual session opening enforces tenant availability and open-session uniqueness`() {
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val inactiveTableId = createStaffTable(RESTAURANT_ID, active = false)
        mockMvc.perform(
            post("/staff/tables/$inactiveTableId/sessions")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("TABLE_UNAVAILABLE"))

        val foreignTableId = createStaffTable(OTHER_RESTAURANT_ID)
        mockMvc.perform(
            post("/staff/tables/$foreignTableId/sessions")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
        ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        val tableId = createStaffTable(RESTAURANT_ID)
        mockMvc.perform(
            post("/staff/tables/$tableId/sessions")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
        ).andExpect(status().isCreated)
        mockMvc.perform(
            post("/staff/tables/$tableId/sessions")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SESSION_ALREADY_OPEN"))
    }

    @Test
    fun `concurrent staff and qr openings create only one session for a table`() {
        val tableId = UUID.randomUUID()
        val tableCode = "RACE-${UUID.randomUUID()}"
        jdbc.update(
            "INSERT INTO dining_table (id, restaurant_id, label, table_code) VALUES (?, ?, ?, ?)",
            tableId,
            RESTAURANT_ID,
            "Race $tableCode",
            tableCode,
        )
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val staff = executor.submit(Callable {
                start.await()
                mockMvc.perform(
                    post("/staff/tables/$tableId/sessions")
                        .header("Authorization", "Bearer $token")
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                ).andReturn().response
            })
            val guest = executor.submit(Callable {
                start.await()
                mockMvc.perform(
                    post("/public/tables/$tableCode/sessions")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"displayName":"Race guest"}"""),
                ).andReturn().response
            })
            start.countDown()
            val responses = listOf(staff.get(20, TimeUnit.SECONDS), guest.get(20, TimeUnit.SECONDS))
            assertEquals(listOf(201, 409), responses.map { it.status }.sorted())
            val conflict = responses.single { it.status == 409 }
            assertEquals("SESSION_ALREADY_OPEN", json(conflict.contentAsString, "$.code"))
            assertEquals(
                1,
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM table_session WHERE table_id = ? AND status = 'OPEN'",
                    Int::class.java,
                    tableId,
                ),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent replay of the same manual opening returns one persisted response`() {
        val tableId = createStaffTable(RESTAURANT_ID)
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val key = UUID.randomUUID().toString()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val requests = (1..2).map {
                executor.submit(Callable {
                    start.await()
                    mockMvc.perform(
                        post("/staff/tables/$tableId/sessions")
                            .header("Authorization", "Bearer $token")
                            .header("Idempotency-Key", key),
                    ).andReturn().response
                })
            }
            start.countDown()
            val responses = requests.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(listOf(201, 201), responses.map { it.status }.sorted())
            assertEquals(1, responses.count { it.getHeader("Idempotent-Replayed") == "true" })
            assertEquals(
                json(responses[0].contentAsString, "$.session.id"),
                json(responses[1].contentAsString, "$.session.id"),
            )
            assertEquals(
                1,
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM table_session WHERE table_id = ? AND status = 'OPEN'",
                    Int::class.java,
                    tableId,
                ),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `station queue enforces permission station scope and cursor pagination`() {
        val fixture = createGuestSession()
        val orderId = createOrder(fixture)
        val stationId = addOrderItem(orderId, fixture)
        addOrderItem(orderId, fixture)
        jdbc.update(
            "UPDATE customer_order SET status = 'ACCEPTED', accepted_at = CURRENT_TIMESTAMP - INTERVAL '20 minutes' WHERE id = ?",
            UUID.fromString(orderId),
        )
        jdbc.update(
            """
            UPDATE order_item oi SET status = 'QUEUED', queued_at = o.accepted_at
            FROM customer_order o WHERE oi.order_id = o.id AND oi.order_id = ?
            """.trimIndent(),
            UUID.fromString(orderId),
        )
        jdbc.update(
            """
            UPDATE order_item
            SET options_snapshot = '[{"groupId":"3f9e1d2c-4b5a-4687-9a0b-c1d2e3f4a5b6","optionId":"e5f6a7b8-c9d0-4e1f-9a2b-3c4d5e6f7081","groupName":"Tamaño","optionName":"Grande","priceDeltaMinor":80}]'::jsonb
            WHERE order_id = ?
            """.trimIndent(),
            UUID.fromString(orderId),
        )
        jdbc.update("DELETE FROM user_role WHERE user_id = ?", USER_ID)
        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'KITCHEN')", USER_ID)
        val kitchenToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")

        mockMvc.perform(
            get("/staff/stations/$stationId/queue").header("Authorization", "Bearer $kitchenToken"),
        ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ITEM_STATION_FORBIDDEN"))
        mockMvc.perform(
            get("/staff/stations").header("Authorization", "Bearer $kitchenToken"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)

        jdbc.update("INSERT INTO user_station (user_id, station_id) VALUES (?, ?)", USER_ID, stationId)
        mockMvc.perform(
            get("/staff/stations").header("Authorization", "Bearer $kitchenToken"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(stationId.toString()))
        val firstPage = mockMvc.perform(
            get("/staff/stations/$stationId/queue")
                .param("limit", "1")
                .header("Authorization", "Bearer $kitchenToken"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].status").value("QUEUED"))
            .andExpect(jsonPath("$.items[0].delayed").value(true))
            .andExpect(jsonPath("$.items[0].options[0].optionName").value("Grande"))
            .andExpect(jsonPath("$.items[0].options[0].priceDeltaMinor").doesNotExist())
            .andReturn().response.contentAsString
        val cursor = json(firstPage, "$.nextCursor")
        val secondPage = mockMvc.perform(
            get("/staff/stations/$stationId/queue")
                .param("limit", "1")
                .param("cursor", cursor)
                .header("Authorization", "Bearer $kitchenToken"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].status").value("QUEUED"))
            .andReturn().response.contentAsString
        assertEquals(
            2,
            setOf(json(firstPage, "$.items[0].itemId"), json(secondPage, "$.items[0].itemId")).size,
        )
        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'OWNER')", USER_ID)
        val ownerKitchenToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        mockMvc.perform(
            get("/staff/stations/$stationId/queue")
                .header("Authorization", listOf("Bearer", ownerKitchenToken).joinToString(" ")),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].options[0].priceDeltaMinor").value(80))

        jdbc.update(
            "INSERT INTO restaurant (id, name, timezone, currency) VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
            OTHER_RESTAURANT_ID,
            "Other restaurant",
            "America/Argentina/Buenos_Aires",
            "ARS",
        )
        jdbc.update(
            "INSERT INTO station (id, restaurant_id, code, name) VALUES (?, ?, 'BAR', 'Other bar')",
            BAR_STATION_ID,
            OTHER_RESTAURANT_ID,
        )
        mockMvc.perform(
            get("/staff/stations/$BAR_STATION_ID/queue").header("Authorization", "Bearer $kitchenToken"),
        ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `station queue preserves historical null timestamps and follows accepted time FIFO`() {
        val recentAccepted = createGuestSession()
        val oldAccepted = createGuestSession()
        val historical = createGuestSession()
        val recentOrderId = createOrder(recentAccepted)
        val oldOrderId = createOrder(oldAccepted)
        val historicalOrderId = createOrder(historical)
        val stationId = addOrderItem(recentOrderId, recentAccepted)
        addOrderItem(oldOrderId, oldAccepted)
        addOrderItem(historicalOrderId, historical)

        jdbc.update(
            "UPDATE customer_order SET status = 'ACCEPTED', accepted_at = CURRENT_TIMESTAMP - INTERVAL '5 minutes' WHERE id = ?",
            UUID.fromString(recentOrderId),
        )
        jdbc.update(
            "UPDATE customer_order SET status = 'ACCEPTED', accepted_at = CURRENT_TIMESTAMP - INTERVAL '20 minutes' WHERE id = ?",
            UUID.fromString(oldOrderId),
        )
        jdbc.update(
            """
            UPDATE order_item SET status = 'QUEUED', queued_at = CURRENT_TIMESTAMP - INTERVAL '30 minutes'
            WHERE order_id = ?
            """.trimIndent(),
            UUID.fromString(recentOrderId),
        )
        jdbc.update(
            """
            UPDATE order_item SET status = 'QUEUED', queued_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
            WHERE order_id = ?
            """.trimIndent(),
            UUID.fromString(oldOrderId),
        )
        jdbc.update(
            "UPDATE customer_order SET status = 'ACCEPTED', accepted_at = NULL WHERE id = ?",
            UUID.fromString(historicalOrderId),
        )
        jdbc.update(
            "UPDATE order_item SET status = 'QUEUED', queued_at = NULL WHERE order_id = ?",
            UUID.fromString(historicalOrderId),
        )
        jdbc.update("DELETE FROM user_role WHERE user_id = ?", USER_ID)
        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'KITCHEN')", USER_ID)
        jdbc.update("INSERT INTO user_station (user_id, station_id) VALUES (?, ?)", USER_ID, stationId)
        val kitchenToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")

        val response = mockMvc.perform(
        get("/staff/stations/$stationId/queue")
            .param("limit", "10")
            .header("Authorization", "Bearer $kitchenToken"),
    ).andExpect(status().isOk)
        .andReturn().response.contentAsString
        val expectedOrderIds = listOf(oldOrderId, recentOrderId, historicalOrderId)
        val matchingItems = JsonPath.read<List<Map<String, Any?>>>(response, "$.items")
            .filter { it["orderId"] in expectedOrderIds }

        assertEquals(3, matchingItems.size)
        assertEquals(expectedOrderIds, matchingItems.map { it["orderId"] })
        assertEquals(true, matchingItems[0]["delayed"])
        assertEquals(false, matchingItems[1]["delayed"])
        assertEquals(null, matchingItems[2]["queuedAt"])
        assertEquals(null, matchingItems[2]["delayed"])
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
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.nextCursor").value(null as String?))

        jdbc.update("DELETE FROM user_role WHERE user_id = ?", USER_ID)
        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'KITCHEN')", USER_ID)
        val stationId = addOrderItem(orderId, fixture)
        addOrderItem(orderId, fixture)
        addOrderItem(orderId, fixture)
        val otherStationId = jdbc.queryForObject(
            "SELECT id FROM station WHERE restaurant_id = ? AND id <> ? LIMIT 1",
            UUID::class.java,
            RESTAURANT_ID,
            stationId,
        )!!
        val itemIds = jdbc.query(
            "SELECT id FROM order_item WHERE order_id = ? ORDER BY id",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            UUID.fromString(orderId),
        )
        jdbc.update("UPDATE order_item SET station_id = ? WHERE id = ?", otherStationId, itemIds.first())
        val assignedItemId = itemIds.last()
        val cancelledItemId = itemIds[1]
        val kitchenToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        mockMvc.perform(
            get("/staff/orders")
                .param("sessionId", fixture.sessionId)
                .header("Authorization", "Bearer $kitchenToken"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.nextCursor").value(null as String?))

        jdbc.update("INSERT INTO user_station (user_id, station_id) VALUES (?, ?)", USER_ID, stationId)
        mockMvc.perform(
            get("/staff/orders")
                .param("sessionId", fixture.sessionId)
                .header("Authorization", "Bearer $kitchenToken"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.nextCursor").value(null as String?))

        mockMvc.perform(
            get("/staff/orders/$orderId")
                .header("Authorization", listOf("Bearer", kitchenToken).joinToString(" ")),
        ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        jdbc.update("UPDATE customer_order SET status = 'ACCEPTED' WHERE id = ?", UUID.fromString(orderId))
        jdbc.update("UPDATE order_item SET status = 'IN_PREPARATION' WHERE order_id = ?", UUID.fromString(orderId))
        jdbc.update("UPDATE order_item SET status = 'CANCELLED' WHERE id = ?", cancelledItemId)
        jdbc.update(
            "UPDATE customer_order SET note = 'nota general privada', total_minor = 300 WHERE id = ?",
            UUID.fromString(orderId),
        )
        jdbc.update(
            """
            UPDATE order_item
            SET options_snapshot = '[{"groupId":"3f9e1d2c-4b5a-4687-9a0b-c1d2e3f4a5b6","optionId":"e5f6a7b8-c9d0-4e1f-9a2b-3c4d5e6f7081","groupName":"Tamaño","optionName":"Grande","priceDeltaMinor":80}]'::jsonb
            WHERE order_id = ?
            """.trimIndent(),
            UUID.fromString(orderId),
        )
        mockMvc.perform(
            get("/staff/orders")
                .param("sessionId", fixture.sessionId)
                .header("Authorization", "Bearer $kitchenToken"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(orderId))
            .andExpect(jsonPath("$.items[0].sessionId").value(fixture.sessionId))
            .andExpect(jsonPath("$.items[0].tableLabel").isNotEmpty)
            .andExpect(jsonPath("$.items[0].createdBy").doesNotExist())
            .andExpect(jsonPath("$.items[0].totalMinor").doesNotExist())
            .andExpect(jsonPath("$.items[0].note").doesNotExist())
            .andExpect(jsonPath("$.items[0].items.length()").value(1))
            .andExpect(jsonPath("$.items[0].items[0].id").value(assignedItemId.toString()))
            .andExpect(jsonPath("$.items[0].items[0].lineTotalMinor").doesNotExist())
            .andExpect(jsonPath("$.items[0].items[0].options[0].priceDeltaMinor").doesNotExist())
            .andExpect(jsonPath("$.items[0].items[0].productCurrentlyAvailable").isBoolean)
            .andExpect(jsonPath("$.nextCursor").value(null as String?))

        mockMvc.perform(
            get("/staff/orders/$orderId").header("Authorization", "Bearer $kitchenToken"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(assignedItemId.toString()))
            .andExpect(jsonPath("$.totalMinor").doesNotExist())
            .andExpect(jsonPath("$.note").doesNotExist())
            .andExpect(jsonPath("$.items[0].unitPriceMinor").doesNotExist())
            .andExpect(jsonPath("$.items[0].options[0].priceDeltaMinor").doesNotExist())

        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'BAR')", USER_ID)
        val barKitchenToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        mockMvc.perform(
            get("/staff/orders")
                .param("sessionId", fixture.sessionId)
                .header("Authorization", listOf("Bearer", barKitchenToken).joinToString(" ")),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].totalMinor").value(300))
            .andExpect(jsonPath("$.items[0].items.length()").value(3))
        mockMvc.perform(
            get("/staff/orders/$orderId")
                .header("Authorization", listOf("Bearer", barKitchenToken).joinToString(" ")),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.totalMinor").value(300))
            .andExpect(jsonPath("$.note").value("nota general privada"))
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.items[0].lineTotalMinor").exists())
        jdbc.update("DELETE FROM user_role WHERE user_id = ? AND role_code = 'BAR'", USER_ID)

        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'OWNER')", USER_ID)
        val ownerKitchenToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        mockMvc.perform(
            get("/staff/orders")
                .param("sessionId", fixture.sessionId)
                .header("Authorization", listOf("Bearer", ownerKitchenToken).joinToString(" ")),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].totalMinor").value(300))
            .andExpect(jsonPath("$.items[0].items.length()").value(3))
        mockMvc.perform(
            get("/staff/orders/$orderId")
                .header("Authorization", listOf("Bearer", ownerKitchenToken).joinToString(" ")),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.totalMinor").value(300))
            .andExpect(jsonPath("$.items.length()").value(3))
        jdbc.update("DELETE FROM user_role WHERE user_id = ? AND role_code = 'OWNER'", USER_ID)

        mockMvc.perform(
            post("/staff/orders/$orderId/accept")
                .header("Authorization", "Bearer $kitchenToken")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `staff order page applies status session filters and stable ascending cursor`() {
        val session = createGuestSession()
        val otherSession = createGuestSession()
        val pendingOrderId = createOrder(session)
        val acceptedOrderId = createOrder(session)
        val rejectedOrderId = createOrder(session)
        val otherOrderId = createOrder(otherSession)
        val tiedAt = java.sql.Timestamp.from(Instant.parse("2026-10-13T10:00:00Z"))
        jdbc.update(
            "UPDATE customer_order SET created_at = ?, status = 'PENDING_REVIEW' WHERE id = ?",
            tiedAt,
            UUID.fromString(pendingOrderId),
        )
        jdbc.update(
            "UPDATE customer_order SET created_at = ?, status = 'ACCEPTED' WHERE id = ?",
            tiedAt,
            UUID.fromString(acceptedOrderId),
        )
        jdbc.update(
            "UPDATE customer_order SET created_at = ?, status = 'REJECTED' WHERE id = ?",
            java.sql.Timestamp.from(Instant.parse("2026-10-13T11:00:00Z")),
            UUID.fromString(rejectedOrderId),
        )
        jdbc.update(
            "UPDATE customer_order SET created_at = ?, status = 'REJECTED' WHERE id = ?",
            java.sql.Timestamp.from(Instant.parse("2026-10-13T09:00:00Z")),
            UUID.fromString(otherOrderId),
        )
        val tiedIds = jdbc.queryForList(
            "SELECT id FROM customer_order WHERE session_id = ? AND created_at = ? ORDER BY id",
            String::class.java,
            UUID.fromString(session.sessionId),
            tiedAt,
        )
        val tiedStatuses = tiedIds.map {
            jdbc.queryForObject(
                "SELECT status FROM customer_order WHERE id = ?",
                String::class.java,
                UUID.fromString(it),
            )
        }
        val accessToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")

        val firstPage = mockMvc.perform(
            get("/staff/orders")
                .param("status", "PENDING_REVIEW,ACCEPTED")
                .param("sessionId", session.sessionId)
                .param("limit", "1")
                .header("Authorization", "Bearer $accessToken"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(tiedIds[0]))
            .andExpect(jsonPath("$.items[0].status").value(tiedStatuses[0]))
            .andExpect(jsonPath("$.items[0].sessionId").value(session.sessionId))
            .andExpect(jsonPath("$.items[0].tableLabel").isNotEmpty)
            .andExpect(jsonPath("$.items[0].createdBy.participantId").value(session.participantId))
            .andExpect(jsonPath("$.items[0].items").isArray)
            .andExpect(jsonPath("$.nextCursor").isNotEmpty)
            .andReturn().response.contentAsString

        mockMvc.perform(
            get("/staff/orders")
                .param("status", "PENDING_REVIEW,ACCEPTED")
                .param("sessionId", session.sessionId)
                .param("limit", "1")
                .param("cursor", json(firstPage, "$.nextCursor"))
                .header("Authorization", "Bearer $accessToken"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(tiedIds[1]))
            .andExpect(jsonPath("$.items[0].status").value(tiedStatuses[1]))
            .andExpect(jsonPath("$.nextCursor").value(null as String?))

        mockMvc.perform(
            get("/staff/orders")
                .param("status", "REJECTED")
                .param("sessionId", session.sessionId)
                .header("Authorization", "Bearer $accessToken"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(rejectedOrderId))

        mockMvc.perform(
            get("/staff/orders")
                .param("status", "UNKNOWN")
                .header("Authorization", "Bearer $accessToken"),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        mockMvc.perform(
            get("/staff/orders")
                .param("limit", "0")
                .header("Authorization", "Bearer $accessToken"),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        mockMvc.perform(
            get("/staff/orders")
                .param("cursor", "malformed")
                .header("Authorization", "Bearer $accessToken"),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `preparation action enforces station assignment persists once and replays idempotently`() {
        val fixture = createGuestSession()
        val orderId = createOrder(fixture)
        val stationId = addOrderItem(orderId, fixture)
        val itemId = jdbc.queryForObject(
            "SELECT id FROM order_item WHERE order_id = ?",
            UUID::class.java,
            UUID.fromString(orderId),
        )!!
        jdbc.update(
            "UPDATE customer_order SET status = 'ACCEPTED', accepted_at = CURRENT_TIMESTAMP WHERE id = ?",
            UUID.fromString(orderId),
        )
        jdbc.update(
            """
            UPDATE order_item oi SET status = 'QUEUED', queued_at = o.accepted_at
            FROM customer_order o WHERE oi.order_id = o.id AND oi.order_id = ?
            """.trimIndent(),
            UUID.fromString(orderId),
        )
        val staleActivity = Instant.now().minusSeconds(3600)
        jdbc.update(
            "UPDATE table_session SET last_activity_at = ? WHERE id = ?",
            java.sql.Timestamp.from(staleActivity),
            UUID.fromString(fixture.sessionId),
        )
        jdbc.update("DELETE FROM user_role WHERE user_id = ?", USER_ID)
        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'KITCHEN')", USER_ID)
        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val key = UUID.randomUUID().toString()

        mockMvc.perform(
            post("/staff/order-items/$itemId/start")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", key),
        ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ITEM_STATION_FORBIDDEN"))
        assertEquals(
            1L,
            jdbc.queryForObject("SELECT version FROM customer_order WHERE id = ?", Long::class.java, UUID.fromString(orderId)),
        )

        jdbc.update("INSERT INTO user_station (user_id, station_id) VALUES (?, ?)", USER_ID, stationId)
        val first = mockMvc.perform(
            post("/staff/order-items/$itemId/start")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", key),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.items[0].status").value("IN_PREPARATION"))
            .andReturn()

        mockMvc.perform(
            post("/staff/order-items/$itemId/start")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", key),
        ).andExpect(status().isOk)
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andExpect(content().json(first.response.contentAsString))

        assertEquals(
            2L,
            jdbc.queryForObject("SELECT version FROM customer_order WHERE id = ?", Long::class.java, UUID.fromString(orderId)),
        )
        assertEquals(
            1,
            jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE target_id = CAST(? AS UUID)", Int::class.java, itemId),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM event_log WHERE aggregate_id = CAST(? AS UUID) AND type = 'item.updated'",
                Int::class.java,
                itemId,
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM event_log WHERE aggregate_id = CAST(? AS UUID) AND type = 'order.updated'",
                Int::class.java,
                orderId,
            ),
        )
        assertEquals(
            "IN_PREPARATION",
            jdbc.queryForObject("SELECT status FROM order_item WHERE id = ?", String::class.java, itemId),
        )
        val lastActivity = jdbc.queryForObject(
            "SELECT last_activity_at FROM table_session WHERE id = ?",
            java.sql.Timestamp::class.java,
            UUID.fromString(fixture.sessionId),
        )!!.toInstant()
        assertTrue(lastActivity.isAfter(staleActivity))
        assertTrue(lastActivity.isAfter(Instant.now().minusSeconds(1800)))

        val otherStationId = jdbc.queryForObject(
            "SELECT id FROM station WHERE restaurant_id = ? AND id <> ? LIMIT 1",
            UUID::class.java,
            RESTAURANT_ID,
            stationId,
        )!!
        jdbc.update("DELETE FROM user_station WHERE user_id = ?", USER_ID)
        jdbc.update("INSERT INTO user_station (user_id, station_id) VALUES (?, ?)", USER_ID, otherStationId)

        mockMvc.perform(
            post("/staff/order-items/$itemId/undo")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
        ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ITEM_STATION_FORBIDDEN"))
        assertEquals(
            2L,
            jdbc.queryForObject("SELECT version FROM customer_order WHERE id = ?", Long::class.java, UUID.fromString(orderId)),
        )
        assertEquals(
            "IN_PREPARATION",
            jdbc.queryForObject("SELECT status FROM order_item WHERE id = ?", String::class.java, itemId),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE target_id = CAST(? AS UUID)",
                Int::class.java,
                itemId,
            ),
        )

        jdbc.update("DELETE FROM user_station WHERE user_id = ?", USER_ID)
        jdbc.update("INSERT INTO user_station (user_id, station_id) VALUES (?, ?)", USER_ID, stationId)
        mockMvc.perform(
            post("/staff/order-items/$itemId/undo")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(3))
            .andExpect(jsonPath("$.items[0].status").value("QUEUED"))

        jdbc.update(
            "UPDATE order_item SET status = 'DELIVERED', delivered_at = CURRENT_TIMESTAMP WHERE id = ?",
            itemId,
        )
        jdbc.update(
            "UPDATE customer_order SET status = 'DELIVERED' WHERE id = ?",
            UUID.fromString(orderId),
        )
        mockMvc.perform(
            post("/staff/order-items/$itemId/undo")
                .header("Authorization", "Bearer $staffToken")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
        ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        assertEquals(
            3L,
            jdbc.queryForObject("SELECT version FROM customer_order WHERE id = ?", Long::class.java, UUID.fromString(orderId)),
        )
        assertEquals(
            "DELIVERED",
            jdbc.queryForObject("SELECT status FROM order_item WHERE id = ?", String::class.java, itemId),
        )
    }

    @Test
    fun `concurrent accept and reject serialize with preparation and preserve activity`() {
        jdbc.update("DELETE FROM user_role WHERE user_id = ?", USER_ID)
        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'OWNER')", USER_ID)
        val staffToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        listOf("accept", "reject").forEach { decision ->
            val fixture = createGuestSession()
            val orderId = createOrder(fixture)
            addOrderItem(orderId, fixture)
            val itemId = jdbc.queryForObject(
                "SELECT id FROM order_item WHERE order_id = ?",
                UUID::class.java,
                UUID.fromString(orderId),
            )!!
            val staleActivity = Instant.now().minusSeconds(3600)
            jdbc.update(
                "UPDATE table_session SET last_activity_at = ? WHERE id = ?",
                java.sql.Timestamp.from(staleActivity),
                UUID.fromString(fixture.sessionId),
            )
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            val responses = try {
                val decisionRequest = executor.submit(Callable {
                    check(start.await(5, TimeUnit.SECONDS))
                    val builder = post("/staff/orders/$orderId/$decision")
                        .header("Authorization", "Bearer $staffToken")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                    if (decision == "reject") {
                        builder.contentType(MediaType.APPLICATION_JSON)
                            .content("""{"reasonCode":"OTHER","reasonText":"Concurrent test"}""")
                    }
                    mockMvc.perform(builder).andReturn().response
                })
                val prepare = executor.submit(Callable {
                    check(start.await(5, TimeUnit.SECONDS))
                    mockMvc.perform(
                        post("/staff/order-items/$itemId/start")
                            .header("Authorization", "Bearer $staffToken")
                            .header("Idempotency-Key", UUID.randomUUID().toString()),
                    ).andReturn().response
                })
                start.countDown()
                decisionRequest.get(15, TimeUnit.SECONDS) to prepare.get(15, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }

            assertEquals(200, responses.first.status)
            assertTrue(responses.second.status == 200 || responses.second.status == 409)
            if (responses.second.status == 409) {
                assertEquals("ITEM_INVALID_TRANSITION", json(responses.second.contentAsString, "$.code"))
            }
            val finalOrderStatus = jdbc.queryForObject(
                "SELECT status FROM customer_order WHERE id = ?",
                String::class.java,
                UUID.fromString(orderId),
            )
            val finalItemStatus = jdbc.queryForObject(
                "SELECT status FROM order_item WHERE id = ?",
                String::class.java,
                itemId,
            )
            if (decision == "accept") {
                assertTrue(finalOrderStatus == "ACCEPTED" || finalOrderStatus == "IN_PREPARATION")
                assertTrue(finalItemStatus == "QUEUED" || finalItemStatus == "IN_PREPARATION")
                assertEquals(if (finalItemStatus == "QUEUED") 2L else 3L, jdbc.queryForObject(
                    "SELECT version FROM customer_order WHERE id = ?",
                    Long::class.java,
                    UUID.fromString(orderId),
                ))
            } else {
                assertEquals("REJECTED", finalOrderStatus)
                assertEquals("CANCELLED", finalItemStatus)
                assertEquals(2L, jdbc.queryForObject(
                    "SELECT version FROM customer_order WHERE id = ?",
                    Long::class.java,
                    UUID.fromString(orderId),
                ))
            }
            val lastActivity = jdbc.queryForObject(
                "SELECT last_activity_at FROM table_session WHERE id = ?",
                java.sql.Timestamp::class.java,
                UUID.fromString(fixture.sessionId),
            )!!.toInstant()
            assertTrue(lastActivity.isAfter(staleActivity))
            assertTrue(lastActivity.isAfter(Instant.now().minusSeconds(1800)))
        }
    }

    @Test
    fun `order creation validates options and persists a historical price snapshot`() {
        val fixture = createGuestSession()
        val productId = UUID.fromString("9a2b4c6d-8e0f-4a1b-b3c5-d7e9f1a3b5c7")
        val largeOptionId = "e5f6a7b8-c9d0-4e1f-9a2b-3c4d5e6f7081"
        val oatOptionId = "f6a7b8c9-d0e1-4f2a-8b3c-4d5e6f708192"
        val basePrice = jdbc.queryForObject(
            "SELECT price_minor FROM product WHERE id = ?",
            Long::class.java,
            productId,
        )!!
        val total = basePrice + 140_000
        val idempotencyKey = UUID.randomUUID().toString()
        val request = """{"items":[{"productId":"$productId","quantity":1,"optionIds":["$largeOptionId","$oatOptionId"]}],"acknowledgedTotalMinor":$total}"""

        val response = mockMvc.perform(
            post("/sessions/current/orders")
                .header("Authorization", "Bearer ${fixture.token}")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.totalMinor").value(total))
            .andExpect(jsonPath("$.items[0].optionsDeltaMinor").value(140_000))
            .andExpect(jsonPath("$.items[0].options.length()").value(2))
            .andReturn().response.contentAsString
        val orderId = UUID.fromString(json(response, "$.id"))
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM event_log WHERE aggregate_id = ? AND type = 'order.created'",
                Int::class.java,
                orderId,
            ),
        )
        mockMvc.perform(
            post("/sessions/current/orders")
                .header("Authorization", "Bearer ${fixture.token}")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request),
        ).andExpect(status().isCreated)
            .andExpect(header().string("Idempotent-Replayed", "true"))
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM event_log WHERE aggregate_id = ? AND type = 'order.created'",
                Int::class.java,
                orderId,
            ),
        )
        val savedSnapshot = jdbc.queryForObject(
            "SELECT options_snapshot::text FROM order_item WHERE order_id = ?",
            String::class.java,
            orderId,
        )!!
        try {
            jdbc.update("UPDATE product_option SET name = 'Renamed', price_delta_minor = 0 WHERE id = ?", UUID.fromString(largeOptionId))
            assertEquals(savedSnapshot, jdbc.queryForObject(
                "SELECT options_snapshot::text FROM order_item WHERE order_id = ?",
                String::class.java,
                orderId,
            ))
        } finally {
            jdbc.update("UPDATE product_option SET name = 'Grande', price_delta_minor = 80000 WHERE id = ?", UUID.fromString(largeOptionId))
        }

        val countBeforeInvalidRequest = jdbc.queryForObject("SELECT count(*) FROM customer_order", Int::class.java)!!
        mockMvc.perform(
            post("/sessions/current/orders")
                .header("Authorization", "Bearer ${fixture.token}")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"items":[{"productId":"$productId","quantity":1,"optionIds":["${UUID.randomUUID()}"]}],"acknowledgedTotalMinor":$basePrice}"""),
        ).andExpect(status().`is`(422))
            .andExpect(jsonPath("$.code").value("OPTION_NOT_FOUND"))
        assertEquals(countBeforeInvalidRequest, jdbc.queryForObject("SELECT count(*) FROM customer_order", Int::class.java))
        val groupId = jdbc.queryForObject(
            "SELECT group_id FROM product_option WHERE id = ?",
            UUID::class.java,
            UUID.fromString(largeOptionId),
        )!!
        try {
            jdbc.update("UPDATE product_option SET active = FALSE WHERE id = ?", UUID.fromString(largeOptionId))
            assertEquals(
                savedSnapshot,
                jdbc.queryForObject(
                    "SELECT options_snapshot::text FROM order_item WHERE order_id = ?",
                    String::class.java,
                    orderId,
                ),
            )
            mockMvc.perform(
                post("/sessions/current/orders")
                    .header("Authorization", "Bearer ${fixture.token}")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"items":[{"productId":"$productId","quantity":1,"optionIds":["$largeOptionId"]}],"acknowledgedTotalMinor":$basePrice}"""),
            ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("OPTION_UNAVAILABLE"))
            jdbc.update("UPDATE product_option SET active = TRUE WHERE id = ?", UUID.fromString(largeOptionId))
            jdbc.update("UPDATE option_group SET active = FALSE WHERE id = ?", groupId)
            mockMvc.perform(
                post("/sessions/current/orders")
                    .header("Authorization", "Bearer ${fixture.token}")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"items":[{"productId":"$productId","quantity":1,"optionIds":["$largeOptionId"]}],"acknowledgedTotalMinor":$basePrice}"""),
            ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("OPTION_UNAVAILABLE"))
        } finally {
            jdbc.update("UPDATE product_option SET active = TRUE WHERE id = ?", UUID.fromString(largeOptionId))
            jdbc.update("UPDATE option_group SET active = TRUE WHERE id = ?", groupId)
        }
        mockMvc.perform(
            post("/sessions/current/orders")
                .header("Authorization", "Bearer ${fixture.token}")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"items":[{"productId":"$productId","quantity":1}],"acknowledgedTotalMinor":$basePrice}"""),
        ).andExpect(status().`is`(422))
            .andExpect(jsonPath("$.code").value("OPTION_SELECTION_INVALID"))
        assertEquals(countBeforeInvalidRequest, jdbc.queryForObject("SELECT count(*) FROM customer_order", Int::class.java))
        try {
            jdbc.update("UPDATE product SET active = FALSE WHERE id = ?", productId)
            mockMvc.perform(
                post("/sessions/current/orders")
                    .header("Authorization", "Bearer ${fixture.token}")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"items":[{"productId":"$productId","quantity":1,"optionIds":["$largeOptionId"]}],"acknowledgedTotalMinor":$basePrice}"""),
            ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("PRODUCT_UNAVAILABLE"))
        } finally {
            jdbc.update("UPDATE product SET active = TRUE WHERE id = ?", productId)
        }
        assertEquals(countBeforeInvalidRequest, jdbc.queryForObject("SELECT count(*) FROM customer_order", Int::class.java))
        val categoryId = jdbc.queryForObject(
            """
            SELECT sub.category_id
            FROM product p
            JOIN subcategory sub ON sub.id = p.subcategory_id
            WHERE p.id = ?
            """.trimIndent(),
            UUID::class.java,
            productId,
        )!!
        try {
            jdbc.update("UPDATE category SET active = FALSE WHERE id = ?", categoryId)
            mockMvc.perform(
                post("/sessions/current/orders")
                    .header("Authorization", "Bearer ${fixture.token}")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"items":[{"productId":"$productId","quantity":1,"optionIds":["$largeOptionId","$oatOptionId"]}],"acknowledgedTotalMinor":$total}"""),
            ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("PRODUCT_UNAVAILABLE"))
        } finally {
            jdbc.update("UPDATE category SET active = TRUE WHERE id = ?", categoryId)
        }
        assertEquals(countBeforeInvalidRequest, jdbc.queryForObject("SELECT count(*) FROM customer_order", Int::class.java))
    }

    @Test
    fun `staff can create an order without a participant and replay it once`() {
        val session = createStaffSession()
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val key = UUID.randomUUID().toString()
        val request = """{"createdByUserId":"${UUID.randomUUID()}","items":[{"productId":"9a2b4c6d-8e0f-4a1b-b3c5-d7e9f1a3b5c7","quantity":1,"optionIds":["e5f6a7b8-c9d0-4e1f-9a2b-3c4d5e6f7081","f6a7b8c9-d0e1-4f2a-8b3c-4d5e6f708192"]}]}"""

        val created = mockMvc.perform(
            post("/staff/sessions/${session.first}/orders")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.createdBy.userId").value(USER_ID.toString()))
            .andReturn().response.contentAsString
        val orderId = UUID.fromString(json(created, "$.id"))
        assertEquals(USER_ID, jdbc.queryForObject(
            "SELECT created_by_user_id FROM customer_order WHERE id = ?", UUID::class.java, orderId,
        ))
        assertEquals(null, jdbc.queryForObject(
            "SELECT participant_id FROM customer_order WHERE id = ?", UUID::class.java, orderId,
        ))
        mockMvc.perform(
            post("/staff/sessions/${session.first}/orders")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request),
        ).andExpect(status().isCreated)
            .andExpect(header().string("Idempotent-Replayed", "true"))
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM customer_order WHERE id = ?", Int::class.java, orderId,
        ))
    }

    @Test
    fun `staff order attributes employee separately from its session participant`() {
        val fixture = createGuestSession()
        val actorId = UUID.randomUUID()
        val token = createStaffToken(actorId, "staff-order-${UUID.randomUUID()}@somphie.demo", "WAITER")
        val request = """{"participantId":"${fixture.participantId}","items":[{"productId":"9a2b4c6d-8e0f-4a1b-b3c5-d7e9f1a3b5c7","quantity":1,"optionIds":["e5f6a7b8-c9d0-4e1f-9a2b-3c4d5e6f7081","f6a7b8c9-d0e1-4f2a-8b3c-4d5e6f708192"]}]}"""

        val response = mockMvc.perform(
            post("/staff/sessions/${fixture.sessionId}/orders")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.createdBy.userId").value(actorId.toString()))
            .andExpect(jsonPath("$.participant.participantId").value(fixture.participantId))
            .andReturn().response.contentAsString
        val orderId = UUID.fromString(json(response, "$.id"))
        assertEquals(actorId, jdbc.queryForObject(
            "SELECT created_by_user_id FROM customer_order WHERE id = ?", UUID::class.java, orderId,
        ))
        assertEquals(UUID.fromString(fixture.participantId), jdbc.queryForObject(
            "SELECT participant_id FROM customer_order WHERE id = ?", UUID::class.java, orderId,
        ))
        mockMvc.perform(
            get("/sessions/current/orders").header("Authorization", "Bearer ${fixture.token}"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].createdBy.userId").value(actorId.toString()))
            .andExpect(jsonPath("$.items[0].participant.participantId").value(fixture.participantId))
    }

    @Test
    fun `staff order creation enforces tenant and permission scope`() {
        jdbc.update(
            "INSERT INTO restaurant (id, name, timezone, currency) VALUES (?, 'Other order tenant', 'UTC', 'CHF') ON CONFLICT (id) DO NOTHING",
            OTHER_RESTAURANT_ID,
        )
        jdbc.update(
            "INSERT INTO restaurant_settings (restaurant_id) VALUES (?) ON CONFLICT (restaurant_id) DO NOTHING",
            OTHER_RESTAURANT_ID,
        )
        val otherTenantSession = createGuestSession(OTHER_RESTAURANT_ID)
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val request = """{"items":[{"productId":"9a2b4c6d-8e0f-4a1b-b3c5-d7e9f1a3b5c7","quantity":1,"optionIds":["e5f6a7b8-c9d0-4e1f-9a2b-3c4d5e6f7081","f6a7b8c9-d0e1-4f2a-8b3c-4d5e6f708192"]}]}"""
        mockMvc.perform(
            post("/staff/sessions/${otherTenantSession.sessionId}/orders")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request),
        ).andExpect(status().isNotFound)

        val foreignParticipant = createGuestSession()
        val localSession = createStaffSession()
        mockMvc.perform(
            post("/staff/sessions/${localSession.first}/orders")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"participantId":"${foreignParticipant.participantId}","items":[{"productId":"9a2b4c6d-8e0f-4a1b-b3c5-d7e9f1a3b5c7","quantity":1}]}"""),
        ).andExpect(status().isNotFound)

        val noPermissionToken = createStaffToken(UUID.randomUUID(), "kitchen-${UUID.randomUUID()}@somphie.demo", "KITCHEN")
        val session = createStaffSession()
        mockMvc.perform(
            post("/staff/sessions/${session.first}/orders")
                .header("Authorization", "Bearer $noPermissionToken")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request),
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
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM event_log WHERE aggregate_id = ? AND type = 'order.updated'",
                Int::class.java,
                UUID.fromString(orderId),
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM event_log WHERE aggregate_type = 'ITEM' AND type = 'item.updated' AND session_id = ?",
                Int::class.java,
                UUID.fromString(fixture.sessionId),
            ),
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
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM event_log WHERE aggregate_id = ? AND type = 'order.updated'",
                Int::class.java,
                UUID.fromString(orderId),
            ),
        )
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
            .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))

        mockMvc.perform(
            get("/orders/not-a-uuid").header("Authorization", "Bearer ${owner.token}"),
        ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))

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
    fun `current guest session restores access and enforces revoked and closed read windows`() {
        val fixture = createGuestSession()
        mockMvc.perform(
            get("/sessions/current").header("Authorization", "Bearer ${fixture.token}"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.session.id").value(fixture.sessionId))
            .andExpect(jsonPath("$.session.status").value("OPEN"))
            .andExpect(jsonPath("$.session.readOnly").value(false))
            .andExpect(jsonPath("$.participant.id").value(fixture.participantId))
            .andExpect(jsonPath("$.participant.role").value("HOST"))
            .andExpect(jsonPath("$.joinCode").isNotEmpty)

        val (memberId, memberToken) = createMemberFixture(fixture)
        mockMvc.perform(
            get("/sessions/current").header("Authorization", "Bearer $memberToken"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.participant.id").value(memberId.toString()))
            .andExpect(jsonPath("$.participant.role").value("MEMBER"))
            .andExpect(jsonPath("$.joinCode").doesNotExist())

        jdbc.update(
            "UPDATE session_participant SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?",
            memberId,
        )
        mockMvc.perform(
            get("/sessions/current").header("Authorization", "Bearer $memberToken"),
        ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("SESSION_ACCESS_REVOKED"))

        val closedAt = Instant.now()
        jdbc.update(
            """
            UPDATE table_session
            SET status = 'CLOSED', closed_at = ?, close_reason = 'EMPTY', join_code = NULL
            WHERE id = ?
            """.trimIndent(),
            java.sql.Timestamp.from(closedAt),
            UUID.fromString(fixture.sessionId),
        )
        mockMvc.perform(
            get("/sessions/current").header("Authorization", "Bearer ${fixture.token}"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.session.status").value("CLOSED"))
            .andExpect(jsonPath("$.session.readOnly").value(true))
            .andExpect(jsonPath("$.joinCode").doesNotExist())

        jdbc.update(
            "UPDATE table_session SET closed_at = ? WHERE id = ?",
            java.sql.Timestamp.from(closedAt.minusSeconds(25 * 60 * 60)),
            UUID.fromString(fixture.sessionId),
        )
        mockMvc.perform(
            get("/sessions/current").header("Authorization", "Bearer ${fixture.token}"),
        ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))
    }

    @Test
    fun `session order list returns contract page with full snapshots and stable cursor`() {
        val owner = createGuestSession()
        val other = createGuestSession()
        val olderOrderId = createOrder(owner)
        val newerOrderId = createOrder(owner)
        val otherOrderId = createOrder(other)
        jdbc.update(
            "UPDATE customer_order SET created_at = ? WHERE id = ?",
            java.sql.Timestamp.from(Instant.parse("2026-10-13T10:00:00Z")),
            UUID.fromString(olderOrderId),
        )
        jdbc.update(
            "UPDATE customer_order SET created_at = ? WHERE id = ?",
            java.sql.Timestamp.from(Instant.parse("2026-10-13T11:00:00Z")),
            UUID.fromString(newerOrderId),
        )
        jdbc.update(
            "UPDATE customer_order SET created_at = ? WHERE id = ?",
            java.sql.Timestamp.from(Instant.parse("2026-10-13T12:00:00Z")),
            UUID.fromString(otherOrderId),
        )
        addOrderItem(newerOrderId, owner)

        val firstPage = mockMvc.perform(
            get("/sessions/current/orders").param("limit", "1").header("Authorization", "Bearer ${owner.token}"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(newerOrderId))
            .andExpect(jsonPath("$.items[0].version").value(1))
            .andExpect(jsonPath("$.items[0].createdBy.participantId").value(owner.participantId))
            .andExpect(jsonPath("$.items[0].items.length()").value(1))
            .andExpect(jsonPath("$.items[0].items[0].options").isArray)
            .andExpect(jsonPath("$.items[0].items[0].status").value("PENDING"))
            .andExpect(jsonPath("$.nextCursor").isNotEmpty)
            .andReturn().response.contentAsString
        val cursor = json(firstPage, "$.nextCursor")

        mockMvc.perform(
            get("/sessions/current/orders")
                .param("limit", "1")
                .param("cursor", cursor)
                .header("Authorization", "Bearer ${owner.token}"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(olderOrderId))
            .andExpect(jsonPath("$.nextCursor").value(null as String?))

        val emptySession = createGuestSession()
        mockMvc.perform(
            get("/sessions/current/orders").header("Authorization", "Bearer ${emptySession.token}"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.nextCursor").value(null as String?))
    }

    @Test
    fun `session order page matches contract and paginates tied timestamps without leaking sessions`() {
        val owner = createGuestSession()
        val other = createGuestSession()
        val orderA = createOrder(owner)
        val orderB = createOrder(owner)
        val isolatedOrder = createOrder(other)
        val tiedAt = java.sql.Timestamp.from(Instant.parse("2026-10-13T11:00:00Z"))
        listOf(orderA, orderB).forEach { id ->
            jdbc.update("UPDATE customer_order SET created_at = ? WHERE id = ?", tiedAt, UUID.fromString(id))
        }
        jdbc.update(
            "UPDATE customer_order SET created_at = ? WHERE id = ?",
            java.sql.Timestamp.from(Instant.parse("2026-10-13T12:00:00Z")),
            UUID.fromString(isolatedOrder),
        )
        val orderedIds = jdbc.queryForList(
            """
            SELECT id FROM customer_order
            WHERE session_id = ? AND created_at = ?
            ORDER BY id DESC
            """.trimIndent(),
            String::class.java,
            UUID.fromString(owner.sessionId),
            tiedAt,
        )
        addOrderItem(requireNotNull(orderedIds.first()), owner)

        val firstPage = mockMvc.perform(
            get("/sessions/current/orders")
                .param("limit", "1")
                .header("Authorization", "Bearer ${owner.token}"),
        ).andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(orderedIds[0]))
            .andExpect(jsonPath("$.items[0].displayCode").value("#SEC"))
            .andExpect(jsonPath("$.items[0].status").value("PENDING_REVIEW"))
            .andExpect(jsonPath("$.items[0].version").value(1))
            .andExpect(jsonPath("$.items[0].createdAt").isNotEmpty)
            .andExpect(jsonPath("$.items[0].createdBy.participantId").value(owner.participantId))
            .andExpect(jsonPath("$.items[0].createdBy.displayName").value("Test guest"))
            .andExpect(jsonPath("$.items[0].totalMinor").isNumber)
            .andExpect(jsonPath("$.items[0].items.length()").value(1))
            .andExpect(jsonPath("$.items[0].items[0].id").isNotEmpty)
            .andExpect(jsonPath("$.items[0].items[0].productId").isNotEmpty)
            .andExpect(jsonPath("$.items[0].items[0].productName").value("Security test item"))
            .andExpect(jsonPath("$.items[0].items[0].productNameI18n").isMap)
            .andExpect(jsonPath("$.items[0].items[0].basePriceMinor").isNumber)
            .andExpect(jsonPath("$.items[0].items[0].optionsDeltaMinor").isNumber)
            .andExpect(jsonPath("$.items[0].items[0].unitPriceMinor").isNumber)
            .andExpect(jsonPath("$.items[0].items[0].quantity").value(1))
            .andExpect(jsonPath("$.items[0].items[0].lineTotalMinor").isNumber)
            .andExpect(jsonPath("$.items[0].items[0].station").isNotEmpty)
            .andExpect(jsonPath("$.items[0].items[0].status").value("PENDING"))
            .andExpect(jsonPath("$.items[0].items[0].options").isArray)
            .andExpect(jsonPath("$.nextCursor").isNotEmpty)
            .andReturn().response.contentAsString

        mockMvc.perform(
            get("/sessions/current/orders")
                .param("limit", "1")
                .param("cursor", json(firstPage, "$.nextCursor"))
                .header("Authorization", "Bearer ${owner.token}"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(orderedIds[1]))
            .andExpect(jsonPath("$.nextCursor").value(null as String?))
    }

    @Test
    fun `order detail returns persisted snapshot fields and timestamps from its session`() {
        val owner = createGuestSession()
        val orderId = createOrder(owner)
        addOrderItem(orderId, owner)
        val queuedAt = Instant.parse("2026-10-13T11:20:00Z")
        val startedAt = Instant.parse("2026-10-13T11:22:00Z")
        jdbc.update(
            "UPDATE customer_order SET status = 'ACCEPTED', note = 'No sugar' WHERE id = ?",
            UUID.fromString(orderId),
        )
        jdbc.update(
            """
            UPDATE order_item SET status = 'IN_PREPARATION', note = 'Oat milk', queued_at = ?, started_at = ?
            WHERE order_id = ?
            """.trimIndent(),
            java.sql.Timestamp.from(queuedAt),
            java.sql.Timestamp.from(startedAt),
            UUID.fromString(orderId),
        )

        mockMvc.perform(
            get("/orders/$orderId").header("Authorization", "Bearer ${owner.token}"),
        ).andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").value(orderId))
            .andExpect(jsonPath("$.displayCode").value("#SEC"))
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.createdAt").isNotEmpty)
            .andExpect(jsonPath("$.createdBy.participantId").value(owner.participantId))
            .andExpect(jsonPath("$.createdBy.displayName").value("Test guest"))
            .andExpect(jsonPath("$.note").value("No sugar"))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").isNotEmpty)
            .andExpect(jsonPath("$.items[0].productId").isNotEmpty)
            .andExpect(jsonPath("$.items[0].productName").value("Security test item"))
            .andExpect(jsonPath("$.items[0].productNameI18n").isMap)
            .andExpect(jsonPath("$.items[0].basePriceMinor").isNumber)
            .andExpect(jsonPath("$.items[0].optionsDeltaMinor").isNumber)
            .andExpect(jsonPath("$.items[0].unitPriceMinor").isNumber)
            .andExpect(jsonPath("$.items[0].quantity").value(1))
            .andExpect(jsonPath("$.items[0].lineTotalMinor").isNumber)
            .andExpect(jsonPath("$.items[0].station").isNotEmpty)
            .andExpect(jsonPath("$.items[0].status").value("IN_PREPARATION"))
            .andExpect(jsonPath("$.items[0].note").value("Oat milk"))
            .andExpect(jsonPath("$.items[0].queuedAt").value(queuedAt.toString()))
            .andExpect(jsonPath("$.items[0].startedAt").value(startedAt.toString()))
            .andExpect(jsonPath("$.items[0].options").isArray)
            .andExpect(jsonPath("$.totalMinor").value(100))
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

    private fun eventCursor(): Long =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(id), 0) FROM event_log WHERE restaurant_id = ?",
            Long::class.java,
            RESTAURANT_ID,
        ) ?: 0L

    private fun scheduledStreamCount(): Int = eventStreamScheduler.activeCount + eventStreamScheduler.queue.size

    private fun awaitScheduledStreamCountAtMost(maximum: Int, timeoutSeconds: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (scheduledStreamCount() > maximum && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
        }
        assertTrue(scheduledStreamCount() <= maximum, "SSE scheduler retained tasks after all responses closed")
    }

    private fun openSessionEventStream(
        token: String,
        cursor: Long,
        client: HttpClient = eventHttpClient,
    ): HttpResponse<InputStream> {
        val response = client.send(
            HttpRequest.newBuilder(URI("http://localhost:$port/events/stream?scope=session"))
                .header("Authorization", "Bearer $token")
                .header("Last-Event-ID", cursor.toString())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        assertEquals(200, response.statusCode())
        return response
    }

    private fun openStaffEventStream(token: String, cursor: Long): HttpResponse<InputStream> {
        val authorization = listOf("Bearer", token).joinToString(" ")
        val response = eventHttpClient.send(
            HttpRequest.newBuilder(URI("http://localhost:$port/events/stream?scope=staff"))
                .header("Authorization", authorization)
                .header("Last-Event-ID", cursor.toString())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        assertEquals(200, response.statusCode())
        return response
    }

    private fun appendSessionEvent(sessionId: UUID): StoredDomainEvent =
        requireNotNull(
            TransactionTemplate(transactionManager).execute {
                eventOutbox.append(
                    DomainEvent(
                        restaurantId = RESTAURANT_ID,
                        type = DomainEventType.SESSION_UPDATED,
                        aggregateType = EventAggregateType.SESSION,
                        aggregateId = sessionId,
                        version = EventVersion.Increment,
                        sessionId = sessionId,
                    ),
                )
            },
        )

    private fun readSseMessage(stream: InputStream): String =
        readSseMessage(BufferedReader(InputStreamReader(stream, Charsets.UTF_8)))

    private fun readSseMessage(reader: BufferedReader): String {
        val lines = mutableListOf<String>()
        while (true) {
            val line = reader.readLine() ?: return lines.joinToString("\n")
            if (line.isEmpty()) {
                if (lines.isNotEmpty()) return lines.joinToString("\n")
            } else {
                lines += line
            }
        }
    }

    private fun readSseEvent(reader: BufferedReader, type: String): String {
        while (true) {
            val message = readSseMessage(reader)
            if (message.contains("event:$type")) return message
        }
    }

    private fun createGuestSession(restaurantId: UUID = RESTAURANT_ID): GuestFixture {
        val tableId = UUID.randomUUID()
        val tableCode = "SEC-${UUID.randomUUID()}"
        jdbc.update(
            "INSERT INTO dining_table (id, restaurant_id, label, table_code) VALUES (?, ?, ?, ?)",
            tableId,
            restaurantId,
            "Security $tableCode",
            tableCode,
        )
        val result = mockMvc.perform(
            post("/public/tables/$tableCode/sessions")
                .header("Idempotency-Key", UUID.randomUUID().toString())
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

    private fun createStaffSession(): Pair<UUID, String> {
        val tableId = UUID.randomUUID()
        val tableCode = "STAFF-${UUID.randomUUID()}"
        val label = "Staff $tableCode"
        val sessionId = UUID.randomUUID()
        val now = java.sql.Timestamp.from(Instant.now())
        jdbc.update(
            "INSERT INTO dining_table (id, restaurant_id, label, table_code) VALUES (?, ?, ?, ?)",
            tableId,
            RESTAURANT_ID,
            label,
            tableCode,
        )
        jdbc.update(
            """
            INSERT INTO table_session (
                id, restaurant_id, table_id, status, opened_at, opened_by_type, opened_by_user_id, last_activity_at
            ) VALUES (?, ?, ?, 'OPEN', ?, 'STAFF', ?, ?)
            """.trimIndent(),
            sessionId,
            RESTAURANT_ID,
            tableId,
            now,
            USER_ID,
            now,
        )
        return sessionId to label
    }

    private fun createStaffToken(userId: UUID, email: String, role: String): String {
        val password = "staff-order-password"
        jdbc.update(
            """
            INSERT INTO app_user (id, restaurant_id, email, password_hash, display_name, status)
            VALUES (?, ?, ?, ?, ?, 'ACTIVE')
            """.trimIndent(),
            userId,
            RESTAURANT_ID,
            email,
            passwordEncoder.encode(password),
            "Staff Order Test",
        )
        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, ?)", userId, role)
        val response = mockMvc.perform(loginRequest(email, password))
            .andExpect(status().isOk)
            .andReturn()
        return json(response.response.contentAsString, "$.accessToken")
    }

    private fun assertSessionRemainsOpen(sessionId: String) {
        assertEquals(
            "OPEN",
            jdbc.queryForObject(
                "SELECT status FROM table_session WHERE id = ?",
                String::class.java,
                UUID.fromString(sessionId),
            ),
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'SESSION_CLOSED' AND target_id = ?",
                Int::class.java,
                UUID.fromString(sessionId),
            ),
        )
    }

    private fun createBillableSession(amountMinor: Long = 500): GuestFixture {
        val fixture = createGuestSession()
        val orderId = createOrder(fixture)
        addOrderItem(orderId, fixture)
        jdbc.update("UPDATE customer_order SET status = 'ACCEPTED' WHERE id = ?", UUID.fromString(orderId))
        jdbc.update(
            "UPDATE order_item SET line_total_minor = ?, unit_price_minor = ? WHERE order_id = ?",
            amountMinor,
            amountMinor,
            UUID.fromString(orderId),
        )
        return fixture
    }

    private fun createOverpaidSession(amountMinor: Long = 200, tipMinor: Long = 0): GuestFixture {
        val fixture = createBillableSession(amountMinor)
        mockMvc.perform(
            post("/sessions/current/payments")
                .header("Authorization", "Bearer ${fixture.token}")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amountMinor":$amountMinor,"tipMinor":$tipMinor,"method":"SIMULATED_ONLINE"}"""),
        ).andExpect(status().isCreated)
        jdbc.update("UPDATE order_item SET status = 'CANCELLED' WHERE session_id = ?", UUID.fromString(fixture.sessionId))
        return fixture
    }

    private fun createStaffTable(restaurantId: UUID, active: Boolean = true): UUID {
        val tableId = UUID.randomUUID()
        val tableCode = "STAFF-${UUID.randomUUID()}"
        jdbc.update(
            """
            INSERT INTO dining_table (id, restaurant_id, label, table_code, active)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            tableId,
            restaurantId,
            "Staff $tableCode",
            tableCode,
            active,
        )
        return tableId
    }

    private fun createOrder(
        fixture: GuestFixture,
        restaurantId: UUID = RESTAURANT_ID,
    ): String {
        val orderId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO customer_order
                (id, restaurant_id, session_id, participant_id, status, display_code, summary, total_minor, created_at)
            VALUES (?, ?, ?, ?, 'PENDING_REVIEW', '#SEC', 'Security test', 100, CURRENT_TIMESTAMP)
            """.trimIndent(),
            orderId,
            restaurantId,
            UUID.fromString(fixture.sessionId),
            UUID.fromString(fixture.participantId),
        )
        return orderId.toString()
    }

    private fun createMember(fixture: GuestFixture): String {
        return createMemberFixture(fixture).second
    }

    private fun createMemberFixture(fixture: GuestFixture): Pair<UUID, String> {
        val token = "gt_${UUID.randomUUID()}"
        val participantId = UUID.randomUUID()
        val tokenHash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
        )
        jdbc.update(
            """
            INSERT INTO session_participant (id, session_id, display_name, role, token_hash)
            VALUES (?, ?, 'Test member', 'MEMBER', ?)
            """.trimIndent(),
            participantId,
            UUID.fromString(fixture.sessionId),
            tokenHash,
        )
        return participantId to token
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

    @Test
    fun `staff table board keeps occupied state and signed balance for negative account`() {
        val fixture = createOverpaidSession(amountMinor = 200)
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val response = mockMvc.perform(
            get("/staff/tables").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk)
            .andReturn().response.contentAsString
        val table = JsonPath.read<List<Map<String, Any>>>(response, "$.items")
            .single { it["sessionId"] == fixture.sessionId }

        assertEquals("OCCUPIED", table["displayStatus"])
        assertEquals(-200L, (table["balanceMinor"] as Number).toLong())
    }

    @Test
    fun `staff availability changes version and outbox once for products and options`() {
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val restaurantId = RESTAURANT_ID
        val productId = UUID.fromString("9a2b4c6d-8e0f-4a1b-b3c5-d7e9f1a3b5c7")
        val optionId = UUID.fromString("e5f6a7b8-c9d0-4e1f-9a2b-3c4d5e6f7081")
        jdbc.update("UPDATE product SET available = TRUE WHERE id = ?", productId)
        jdbc.update("UPDATE product_option SET available = TRUE WHERE id = ?", optionId)
        val initialVersion = jdbc.queryForObject(
            "SELECT catalog_version FROM restaurant WHERE id = ?",
            Long::class.java,
            restaurantId,
        )!!
        val initialEventCount = jdbc.queryForObject(
            "SELECT count(*) FROM event_log WHERE restaurant_id = ? AND type = 'catalog.updated'",
            Int::class.java,
            restaurantId,
        )!!
        val previousMenuEtag = mockMvc.perform(get("/menu"))
            .andExpect(status().isOk)
            .andReturn().response.getHeader(HttpHeaders.ETAG)!!

        val productRoute = "/staff/products/$productId/availability"
        repeat(2) {
            mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(productRoute)
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"available":false}"""),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(productId.toString()))
                .andExpect(jsonPath("$.available").value(false))
        }
        assertEquals(
            initialVersion + 1,
            jdbc.queryForObject("SELECT catalog_version FROM restaurant WHERE id = ?", Long::class.java, restaurantId),
        )
        assertEquals(
            initialEventCount + 1,
            jdbc.queryForObject(
                "SELECT count(*) FROM event_log WHERE restaurant_id = ? AND type = 'catalog.updated'",
                Int::class.java,
                restaurantId,
            ),
        )

        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/staff/options/$optionId/availability")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"available":false}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.available").value(false))

        val finalVersion = initialVersion + 2
        assertEquals(
            finalVersion,
            jdbc.queryForObject("SELECT catalog_version FROM restaurant WHERE id = ?", Long::class.java, restaurantId),
        )
        assertEquals(
            listOf(initialVersion + 1, finalVersion),
            jdbc.query(
                "SELECT version FROM event_log WHERE restaurant_id = ? AND type = 'catalog.updated' AND version > ? ORDER BY version",
                { result, _ -> result.getLong("version") },
                // El estado previo de esta prueba puede incluir eventos de otras pruebas del tenant.
                restaurantId,
                initialVersion,
            ),
        )
        mockMvc.perform(get("/menu"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.catalogVersion").value(finalVersion))
            .andExpect(header().string("ETag", "W/\"$finalVersion-es-AR\""))
        mockMvc.perform(get("/menu").header(HttpHeaders.IF_NONE_MATCH, previousMenuEtag))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.catalogVersion").value(finalVersion))
            .andExpect(header().string("ETag", "W/\"$finalVersion-es-AR\""))
    }

    @Test
    fun `catalog image alt is required by persistence and returned by product API`() {
        val productId = UUID.fromString("9a2b4c6d-8e0f-4a1b-b3c5-d7e9f1a3b5c7")

        mockMvc.perform(get("/products/$productId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.image.alt").value("Flat white en taza de cerámica"))

        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update("UPDATE product SET image_alt = NULL WHERE id = ?", productId)
        }
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update("UPDATE product SET image_alt = E' \\t\\n' WHERE id = ?", productId)
        }
        assertEquals(
            "Flat white en taza de cerámica",
            jdbc.queryForObject("SELECT image_alt FROM product WHERE id = ?", String::class.java, productId),
        )
    }

    @Test
    fun `staff availability serializes concurrent requests and rejects missing permission or foreign tenant`() {
        val token = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        val productId = UUID.fromString("9a2b4c6d-8e0f-4a1b-b3c5-d7e9f1a3b5c7")
        val route = "/staff/products/$productId/availability"
        jdbc.update("UPDATE product SET available = TRUE WHERE id = ?", productId)
        val before = jdbc.queryForObject(
            "SELECT catalog_version FROM restaurant WHERE id = ?",
            Long::class.java,
            RESTAURANT_ID,
        )!!
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = (1..2).map {
                executor.submit<Int> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(route)
                            .header("Authorization", "Bearer $token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"available":false}"""),
                    ).andReturn().response.status
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(listOf(200, 200), attempts.map { it.get(15, TimeUnit.SECONDS) }.sorted())
        } finally {
            executor.shutdownNow()
        }
        assertEquals(
            before + 1,
            jdbc.queryForObject("SELECT catalog_version FROM restaurant WHERE id = ?", Long::class.java, RESTAURANT_ID),
        )

        jdbc.update("DELETE FROM user_role WHERE user_id = ?", USER_ID)
        jdbc.update("INSERT INTO role (code) VALUES ('NO_AVAILABILITY_TEST') ON CONFLICT (code) DO NOTHING")
        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'NO_AVAILABILITY_TEST')", USER_ID)
        val restrictedToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(route)
                .header("Authorization", "Bearer $restrictedToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"available":true}"""),
        ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))

        jdbc.update("DELETE FROM user_role WHERE user_id = ?", USER_ID)
        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'WAITER')", USER_ID)
        val foreignRestaurant = UUID.randomUUID()
        val foreignStation = UUID.randomUUID()
        val foreignCategory = UUID.randomUUID()
        val foreignSubcategory = UUID.randomUUID()
        val foreignProduct = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO restaurant (id, name, timezone, currency) VALUES (?, 'Other tenant', 'UTC', 'CHF')",
            foreignRestaurant,
        )
        try {
            jdbc.update(
                "INSERT INTO station (id, restaurant_id, code, name) VALUES (?, ?, 'BAR', 'Other bar')",
                foreignStation,
                foreignRestaurant,
            )
            jdbc.update(
                "INSERT INTO category (id, restaurant_id, name) VALUES (?, ?, 'Other category')",
                foreignCategory,
                foreignRestaurant,
            )
            jdbc.update(
                "INSERT INTO subcategory (id, category_id, name) VALUES (?, ?, 'Other subcategory')",
                foreignSubcategory,
                foreignCategory,
            )
            jdbc.update(
                """
                INSERT INTO product (id, subcategory_id, station_id, name, price_minor)
                VALUES (?, ?, ?, 'Other product', 100)
                """.trimIndent(),
                foreignProduct,
                foreignSubcategory,
                foreignStation,
            )
            val restoredToken = json(login(OLD_PASSWORD).response.contentAsString, "$.accessToken")
            mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                    "/staff/products/$foreignProduct/availability",
                )
                    .header("Authorization", "Bearer $restoredToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"available":false}"""),
            ).andExpect(status().isNotFound)
        } finally {
            jdbc.update("DELETE FROM product WHERE id = ?", foreignProduct)
            jdbc.update("DELETE FROM restaurant WHERE id = ?", foreignRestaurant)
        }
    }

    @Test
    fun `outbox event and aggregate version roll back with the business transaction`() {
        withEventRestaurant { restaurantId ->
            val aggregateId = UUID.randomUUID()
            val transaction = TransactionTemplate(transactionManager)
            transaction.executeWithoutResult { status ->
                eventOutbox.append(event(restaurantId, aggregateId, EventVersion.Increment))
                status.setRollbackOnly()
            }

            assertEquals(
                0,
                jdbc.queryForObject("SELECT count(*) FROM event_log WHERE restaurant_id = ?", Int::class.java, restaurantId),
            )
            assertEquals(
                0,
                jdbc.queryForObject("SELECT count(*) FROM event_cursor WHERE restaurant_id = ?", Int::class.java, restaurantId),
            )
            assertEquals(
                0,
                jdbc.queryForObject(
                    "SELECT count(*) FROM event_aggregate_version WHERE restaurant_id = ? AND aggregate_id = ?",
                    Int::class.java,
                    restaurantId,
                    aggregateId,
                ),
            )
        }
    }

    @Test
    fun `outbox cursor and versions follow commit order under concurrent appends`() {
        withEventRestaurant { restaurantId ->
            val aggregateId = UUID.randomUUID()
            val firstAppended = CountDownLatch(1)
            val secondCalling = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val first = executor.submit<StoredDomainEvent> {
                    TransactionTemplate(transactionManager).execute {
                        val stored = eventOutbox.append(event(restaurantId, aggregateId, EventVersion.Increment))
                        firstAppended.countDown()
                        check(releaseFirst.await(10, TimeUnit.SECONDS))
                        stored
                    }
                }
                check(firstAppended.await(10, TimeUnit.SECONDS))
                val second = executor.submit<StoredDomainEvent> {
                    secondCalling.countDown()
                    TransactionTemplate(transactionManager).execute {
                        eventOutbox.append(event(restaurantId, aggregateId, EventVersion.Increment))
                    }
                }
                check(secondCalling.await(10, TimeUnit.SECONDS))
                releaseFirst.countDown()

                assertEquals(1L, first.get(15, TimeUnit.SECONDS).eventId)
                assertEquals(2L, second.get(15, TimeUnit.SECONDS).eventId)
                assertEquals(
                    listOf(1L, 2L),
                    jdbc.query(
                        "SELECT version FROM event_log WHERE restaurant_id = ? ORDER BY id",
                        { result, _ -> result.getLong("version") },
                        restaurantId,
                    ),
                )
            } finally {
                releaseFirst.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `outbox rejects a persisted aggregate version replay without adding an event`() {
        withEventRestaurant { restaurantId ->
            val aggregateId = UUID.randomUUID()
            val transaction = TransactionTemplate(transactionManager)
            transaction.executeWithoutResult {
                eventOutbox.append(event(restaurantId, aggregateId, EventVersion.Persisted(3)))
            }

            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                transaction.executeWithoutResult {
                    eventOutbox.append(event(restaurantId, aggregateId, EventVersion.Persisted(3)))
                }
            }
            assertEquals(
                1,
                jdbc.queryForObject("SELECT count(*) FROM event_log WHERE restaurant_id = ?", Int::class.java, restaurantId),
            )
        }
    }

    private fun event(restaurantId: UUID, aggregateId: UUID, version: EventVersion) =
        DomainEvent(
            restaurantId = restaurantId,
            type = DomainEventType.ORDER_UPDATED,
            aggregateType = EventAggregateType.ORDER,
            aggregateId = aggregateId,
            version = version,
        )

    private fun withEventRestaurant(block: (UUID) -> Unit) {
        val restaurantId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO restaurant (id, name, timezone, currency) VALUES (?, 'Outbox test', 'UTC', 'CHF')",
            restaurantId,
        )
        try {
            block(restaurantId)
        } finally {
            jdbc.update("DELETE FROM restaurant WHERE id = ?", restaurantId)
        }
    }

    private companion object {
        const val CONCURRENT_SSE_CONNECTIONS = 50
        const val EMAIL = "identity-test@somphie.demo"
        const val OLD_PASSWORD = "old-password-123"
        const val NEW_PASSWORD = "new-password-456"
        const val OTHER_EMAIL = "identity-other@somphie.demo"
        val USER_ID = UUID.fromString("e6b8be16-51c5-4835-99f1-10d2259ee099")
        val OTHER_USER_ID = UUID.fromString("99bdfa60-0c8d-4e4a-b5d2-d605098b7277")
        val OTHER_RESTAURANT_ID = UUID.fromString("808a73aa-2d94-42a7-8662-5b874f29cc7e")
        val BAR_STATION_ID = UUID.fromString("aa2a73aa-2d94-42a7-8662-5b874f29cc7e")
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
