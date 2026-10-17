package com.somphie.floor.api

import com.somphie.events.DomainEvent
import com.somphie.events.DomainEventType
import com.somphie.events.EventAggregateType
import com.somphie.events.EventOutboxService
import com.somphie.events.EventVersion
import com.somphie.floor.application.ParticipantRevocationService
import com.somphie.floor.domain.JoinSessionRequest
import com.somphie.floor.domain.OpenSessionRequest
import com.somphie.floor.domain.ParticipantInfoDto
import com.somphie.floor.domain.PublicTable
import com.somphie.floor.domain.SessionAccessResponse
import com.somphie.floor.domain.SessionInfoDto
import com.somphie.floor.infrastructure.JoinCodeGenerator
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.idempotency.IdempotencyService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

@RestController
class FloorController(
    private val jdbc: NamedParameterJdbcTemplate,
    private val joinSessionService: JoinSessionService,
    private val joinCodeGenerator: JoinCodeGenerator,
    private val participantRevocationService: ParticipantRevocationService,
    private val clock: Clock,
    private val eventOutbox: EventOutboxService,
    private val idempotencyService: IdempotencyService,
) {

    private val secureRandom = SecureRandom()

    @GetMapping("/public/tables/{tableCode}")
    fun getPublicTable(@PathVariable tableCode: String): ResponseEntity<PublicTable> {
        val rows = jdbc.query(
            "SELECT dt.id AS table_id, dt.label, dt.restaurant_id, dt.active, ts.id AS session_id, ts.status AS session_status FROM dining_table dt LEFT JOIN table_session ts ON dt.id = ts.table_id AND ts.status = 'OPEN' WHERE dt.table_code = :tableCode",
            mapOf("tableCode" to tableCode)
        ) { rs, _ ->
            val active = rs.getBoolean("active")
            val sessionId = rs.getString("session_id")
            val tableLabel = rs.getString("label")
            val restaurantId = rs.getString("restaurant_id")

            PublicTable(
                tableLabel = tableLabel,
                restaurantId = restaurantId,
                restaurantOpen = active,
                hasActiveSession = sessionId != null,
                canOpen = active && sessionId == null
            )
        }

        val table = rows.firstOrNull() ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Table not found")
        return ResponseEntity.ok(table)
    }

    @PostMapping("/public/tables/{tableCode}/sessions")
    @Transactional
    fun openSession(
        @PathVariable tableCode: String,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody request: OpenSessionRequest,
    ): ResponseEntity<Map<String, Any>> {
        val parsedKey = requireUuidV4IdempotencyKey(idempotencyKey)
        val scope = "floor:session:open:$tableCode"
        return idempotencyService.executeIdempotent(scope, parsedKey, request.toString()) {
            openSessionResponse(tableCode, request)
        }
    }

    private fun openSessionResponse(
        tableCode: String,
        request: OpenSessionRequest,
    ): ResponseEntity<Map<String, Any>> {
        val tableSql = "SELECT id, restaurant_id, label, active FROM dining_table WHERE table_code = :tableCode FOR UPDATE"
        val tables = jdbc.query(tableSql, mapOf("tableCode" to tableCode)) { rs, _ ->
            mapOf(
                "id" to rs.getString("id"),
                "restaurant_id" to rs.getString("restaurant_id"),
                "label" to rs.getString("label"),
                "active" to rs.getBoolean("active"),
            )
        }
        val table = tables.firstOrNull() ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Table not found")
        if (table["active"] != true) {
            throw DomainException(ErrorCode.TABLE_UNAVAILABLE, "Table is not available")
        }

        val tableId = table["id"] as String
        val restaurantId = table["restaurant_id"] as String
        val tableLabel = table["label"] as String

        val existingSession = jdbc.queryForObject(
            "SELECT COUNT(1) FROM table_session WHERE table_id = CAST(:tableId AS UUID) AND status = 'OPEN'",
            mapOf("tableId" to tableId),
            Int::class.java
        ) ?: 0

        if (existingSession > 0) {
            throw DomainException(ErrorCode.SESSION_ALREADY_OPEN, "Session already open for this table")
        }

        val sessionId = UUID.randomUUID().toString()
        val participantId = UUID.randomUUID().toString()
        val joinCode = joinCodeGenerator.generate().value
        val guestToken = generateGuestToken()
        val tokenHash = hashToken(guestToken)
        val now = Instant.now(clock)

        val insertSessionSql = """
            INSERT INTO table_session (id, restaurant_id, table_id, status, join_code, opened_at, opened_by_type, last_activity_at)
            VALUES (CAST(:sessionId AS UUID), CAST(:restaurantId AS UUID), CAST(:tableId AS UUID), 'OPEN', :joinCode, :openedAt, 'CUSTOMER', :lastActivityAt)
        """
        jdbc.update(
            insertSessionSql,
            mapOf(
                "sessionId" to sessionId,
                "restaurantId" to restaurantId,
                "tableId" to tableId,
                "joinCode" to joinCode,
                "openedAt" to java.sql.Timestamp.from(now),
                "lastActivityAt" to java.sql.Timestamp.from(now),
            ),
        )

        val insertParticipantSql = """
            INSERT INTO session_participant (id, session_id, display_name, role, token_hash, joined_at)
            VALUES (CAST(:participantId AS UUID), CAST(:sessionId AS UUID), :displayName, 'HOST', :tokenHash, :joinedAt)
        """
        jdbc.update(
            insertParticipantSql,
            mapOf(
                "participantId" to participantId,
                "sessionId" to sessionId,
                "displayName" to request.displayName,
                "tokenHash" to tokenHash,
                "joinedAt" to java.sql.Timestamp.from(now),
            ),
        )
        appendSessionAndTableUpdates(UUID.fromString(restaurantId), UUID.fromString(sessionId), UUID.fromString(tableId))

        val response = SessionAccessResponse(
            session = SessionInfoDto(
                id = sessionId,
                status = "OPEN",
                tableLabel = tableLabel,
                openedAt = now,
            ),
            participant = ParticipantInfoDto(
                id = participantId,
                displayName = request.displayName,
                role = "HOST",
            ),
            guestToken = guestToken,
            joinCode = joinCode,
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(
            mapOf(
                "session" to response.session,
                "participant" to response.participant,
                "guestToken" to response.guestToken,
                "joinCode" to requireNotNull(response.joinCode),
            ),
        )
    }

    @PostMapping("/public/tables/{tableCode}/join")
    fun joinSession(
        @PathVariable tableCode: String,
        @Valid @RequestBody request: JoinSessionRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<SessionAccessResponse> {
        return when (val result = joinSessionService.join(
            tableCode,
            request.displayName,
            request.joinCode,
            // Usar la IP del socket; confiar en cabeceras forwarded requiere configurar un proxy de confianza.
            servletRequest.remoteAddr,
        )) {
            is JoinSessionResult.Joined -> ResponseEntity.status(HttpStatus.CREATED).body(result.response)
            is JoinSessionResult.Rejected -> throw DomainException(
                result.code,
                if (result.code == ErrorCode.JOIN_CODE_INVALID) "Join code is invalid" else "No active session for this table",
                result.extra,
            )
        }
    }

    @GetMapping("/sessions/current")
    fun getCurrentSession(
        @RequestHeader(name = "Authorization", required = false) authorization: String?,
    ): ResponseEntity<Map<String, Any>> {
        val token = authorization
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.takeIf(String::isNotBlank)
            ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "Guest token is required")
        val tokenHash = hashToken(token)
        val current = jdbc.query(
            """
            SELECT ts.id AS session_id, ts.status AS session_status, ts.table_id,
                   ts.opened_at, ts.closed_at, ts.join_code, dt.label AS table_label,
                   sp.id AS participant_id, sp.display_name, sp.role, sp.revoked_at
            FROM session_participant sp
            JOIN table_session ts ON ts.id = sp.session_id
            JOIN dining_table dt ON dt.id = ts.table_id
            WHERE sp.token_hash = :tokenHash
            """.trimIndent(),
            mapOf("tokenHash" to tokenHash),
        ) { rs, _ ->
            CurrentSessionRecord(
                sessionId = rs.getObject("session_id", UUID::class.java),
                sessionStatus = rs.getString("session_status"),
                openedAt = rs.getTimestamp("opened_at").toInstant(),
                closedAt = rs.getTimestamp("closed_at")?.toInstant(),
                joinCode = rs.getString("join_code"),
                tableLabel = rs.getString("table_label"),
                participantId = rs.getObject("participant_id", UUID::class.java),
                displayName = rs.getString("display_name"),
                role = rs.getString("role"),
                revoked = rs.getTimestamp("revoked_at") != null,
            )
        }.firstOrNull() ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "Invalid guest token")

        if (current.revoked) {
            throw DomainException(ErrorCode.SESSION_ACCESS_REVOKED, "Guest access was revoked")
        }
        val readOnly = current.sessionStatus == "CLOSED"
        if (readOnly &&
            (current.closedAt == null ||
                current.closedAt.isBefore(clock.instant().minus(CLOSED_SESSION_READ_WINDOW))
                )
        ) {
            throw DomainException(ErrorCode.TOKEN_EXPIRED, "Closed session access has expired")
        }

        val body = linkedMapOf<String, Any>(
            "session" to SessionInfoDto(
                id = current.sessionId.toString(),
                status = current.sessionStatus,
                tableLabel = current.tableLabel,
                openedAt = current.openedAt,
                readOnly = readOnly,
            ),
            "participant" to ParticipantInfoDto(
                id = current.participantId.toString(),
                displayName = current.displayName,
                role = current.role,
            ),
        )
        if (!readOnly && current.role == "HOST" && current.joinCode != null) {
            body["joinCode"] = current.joinCode
        }
        return ResponseEntity.ok(body)
    }

    @DeleteMapping("/sessions/current/participants/{participantId}")
    fun revokeParticipant(
        @PathVariable participantId: String,
        @RequestHeader(name = "Authorization", required = false) authorization: String?,
    ): ResponseEntity<Map<String, Any>> {
        val participantUuid = try {
            UUID.fromString(participantId)
        } catch (_: IllegalArgumentException) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid participantId")
        }
        return ResponseEntity.ok(participantRevocationService.revokeForHost(authorization, participantUuid))
    }

    private fun generateGuestToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return "gt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun requireUuidV4IdempotencyKey(key: String?): String {
        if (key.isNullOrBlank()) {
            throw DomainException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key header is required")
        }
        val parsed = try {
            UUID.fromString(key)
        } catch (_: IllegalArgumentException) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key must be a UUID v4")
        }
        if (parsed.version() != 4) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key must be a UUID v4")
        }
        return parsed.toString()
    }

    private fun hashToken(token: String): String {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
    }

    private data class CurrentSessionRecord(
        val sessionId: UUID,
        val sessionStatus: String,
        val openedAt: Instant,
        val closedAt: Instant?,
        val joinCode: String?,
        val tableLabel: String,
        val participantId: UUID,
        val displayName: String,
        val role: String,
        val revoked: Boolean,
    )

    private companion object {
        val CLOSED_SESSION_READ_WINDOW = java.time.Duration.ofHours(24)
    }

    private fun appendSessionAndTableUpdates(restaurantId: UUID, sessionId: UUID, tableId: UUID) {
        eventOutbox.append(
            DomainEvent(
                restaurantId,
                DomainEventType.SESSION_UPDATED,
                EventAggregateType.SESSION,
                sessionId,
                EventVersion.Increment,
                sessionId,
            ),
        )
        eventOutbox.append(
            DomainEvent(
                restaurantId,
                DomainEventType.TABLE_UPDATED,
                EventAggregateType.TABLE,
                tableId,
                EventVersion.Increment,
                sessionId,
            ),
        )
    }
}
