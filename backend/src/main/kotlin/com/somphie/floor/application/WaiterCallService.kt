package com.somphie.floor.application

import com.somphie.events.DomainEvent
import com.somphie.events.DomainEventType
import com.somphie.events.EventAggregateType
import com.somphie.events.EventOutboxService
import com.somphie.events.EventVersion
import com.somphie.floor.domain.CreateWaiterCallRequest
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.idempotency.IdempotencyService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

@Service
class WaiterCallService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val idempotency: IdempotencyService,
    private val eventOutbox: EventOutboxService,
    private val clock: Clock,
) {
    private data class GuestAccess(
        val sessionId: UUID,
        val participantId: UUID,
        val restaurantId: UUID,
        val sessionStatus: String,
        val closedAt: Instant?,
        val revoked: Boolean,
    )

    private data class CallRecord(
        val id: UUID,
        val sessionId: UUID,
        val reason: String,
        val text: String?,
        val status: String,
        val createdAt: Instant,
        val acknowledgedAt: Instant?,
        val resolvedAt: Instant?,
        val tableLabel: String?,
    )

    @Transactional
    fun create(
        authorization: String?,
        idempotencyKey: String?,
        request: CreateWaiterCallRequest,
    ): ResponseEntity<Map<String, Any>> {
        val guest = guestAccess(authorization, lockSession = true)
        if (guest.sessionStatus != "OPEN") {
            throw DomainException(ErrorCode.SESSION_CLOSED, "Waiter calls require an open session")
        }
        if (request.reason !in REASONS) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid waiter call reason")
        }
        val now = clock.instant()
        return idempotency.executeIdempotent(
            scope = "waiter-call:create:${guest.sessionId}:${guest.participantId}",
            idempotencyKey = idempotencyKey,
            requestBody = request.toString(),
        ) {
            expireSessionCalls(guest.sessionId, now)
            val active = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM waiter_call
                WHERE session_id = :sessionId AND reason = :reason
                  AND status IN ('OPEN', 'ACKNOWLEDGED')
                """.trimIndent(),
                mapOf("sessionId" to guest.sessionId, "reason" to request.reason),
                Int::class.java,
            ) ?: 0
            if (active > 0) {
                throw DomainException(
                    ErrorCode.WAITER_CALL_ALREADY_ACTIVE,
                    "A waiter call for this reason is already active",
                )
            }

            val lastResolvedAt = jdbc.query(
                """
                SELECT resolved_at
                FROM waiter_call
                WHERE session_id = :sessionId AND reason = :reason AND status = 'RESOLVED'
                ORDER BY resolved_at DESC
                LIMIT 1
                """.trimIndent(),
                mapOf("sessionId" to guest.sessionId, "reason" to request.reason),
            ) { rs, _ -> rs.getTimestamp("resolved_at").toInstant() }.firstOrNull()
            val cooldownSeconds = jdbc.queryForObject(
                """
                SELECT waiter_call_cooldown_sec
                FROM restaurant_settings
                WHERE restaurant_id = :restaurantId
                """.trimIndent(),
                mapOf("restaurantId" to guest.restaurantId),
                Int::class.java,
            ) ?: throw IllegalStateException("Restaurant waiter-call cooldown is not configured")
            val retryAfter = lastResolvedAt?.let { cooldownSeconds(now, it, cooldownSeconds) } ?: 0
            if (retryAfter > 0) {
                throw DomainException(
                    ErrorCode.WAITER_CALL_COOLDOWN,
                    "Wait before creating another waiter call for this reason",
                    mapOf("retryAfterSec" to retryAfter),
                )
            }

            val id = UUID.randomUUID()
            jdbc.update(
                """
                INSERT INTO waiter_call
                    (id, session_id, participant_id, reason, text, status, created_at, expires_at)
                VALUES
                    (:id, :sessionId, :participantId, :reason, :text, 'OPEN', :now, :expiresAt)
                """.trimIndent(),
                mapOf(
                    "id" to id,
                    "sessionId" to guest.sessionId,
                    "participantId" to guest.participantId,
                    "reason" to request.reason,
                    "text" to request.text,
                    "now" to java.sql.Timestamp.from(now),
                    "expiresAt" to java.sql.Timestamp.from(now.plus(CALL_TTL)),
                ),
            )
            appendWaiterEvent(guest.restaurantId, guest.sessionId, DomainEventType.WAITER_CALLED)
            ResponseEntity.status(HttpStatus.CREATED).body(
                waiterCallMap(
                    CallRecord(
                        id = id,
                        sessionId = guest.sessionId,
                        reason = request.reason,
                        text = request.text,
                        status = "OPEN",
                        createdAt = now,
                        acknowledgedAt = null,
                        resolvedAt = null,
                        tableLabel = null,
                    ),
                ),
            )
        }
    }

    @Transactional
    fun listForGuest(authorization: String?): Map<String, Any> {
        val guest = guestAccess(authorization)
        val now = clock.instant()
        expireSessionCalls(guest.sessionId, now)
        return mapOf("items" to sessionCalls(guest.sessionId).map(::waiterCallMap))
    }

    @Transactional
    fun listForStaff(restaurantId: UUID, statuses: List<String>?): Map<String, Any> {
        val selected = statuses?.flatMap { it.split(',') }?.map(String::trim)?.distinct()
            ?.takeIf(List<String>::isNotEmpty)
            ?: listOf("OPEN", "ACKNOWLEDGED")
        if (selected.any { it !in STATUSES }) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid waiter call status")
        }
        expireRestaurantCalls(restaurantId, clock.instant())
        val items = jdbc.query(
            """
            SELECT wc.id, wc.session_id, wc.reason, wc.text, wc.status, wc.created_at,
                   wc.acknowledged_at, wc.resolved_at, dt.label AS table_label
            FROM waiter_call wc
            JOIN table_session ts ON ts.id = wc.session_id
            JOIN dining_table dt ON dt.id = ts.table_id
            WHERE ts.restaurant_id = :restaurantId AND wc.status IN (:statuses)
            ORDER BY wc.created_at, wc.id
            """.trimIndent(),
            mapOf("restaurantId" to restaurantId, "statuses" to selected),
        ) { rs, _ -> rs.toCallRecord() }
        return mapOf("items" to items.map(::staffWaiterCallMap))
    }

    @Transactional(noRollbackFor = [DomainException::class])
    fun acknowledge(id: UUID, restaurantId: UUID): Map<String, Any> =
        transition(id, restaurantId, expected = "OPEN", target = "ACKNOWLEDGED")

    @Transactional(noRollbackFor = [DomainException::class])
    fun resolve(id: UUID, restaurantId: UUID): Map<String, Any> =
        transition(id, restaurantId, expected = "ACKNOWLEDGED", target = "RESOLVED")

    @Transactional(propagation = Propagation.MANDATORY)
    fun activeSessionIds(sessionIds: List<UUID>, restaurantId: UUID): Set<UUID> {
        if (sessionIds.isEmpty()) return emptySet()
        expireSessions(sessionIds, clock.instant())
        return jdbc.query(
            """
            SELECT DISTINCT wc.session_id
            FROM waiter_call wc
            JOIN table_session ts ON ts.id = wc.session_id
            WHERE wc.session_id IN (:sessionIds) AND ts.restaurant_id = :restaurantId
              AND wc.status IN ('OPEN', 'ACKNOWLEDGED')
            """.trimIndent(),
            mapOf("sessionIds" to sessionIds, "restaurantId" to restaurantId),
        ) { rs, _ -> rs.getObject("session_id", UUID::class.java) }.toSet()
    }

    @Transactional
    fun expireCallsAt(now: Instant): Int {
        val expiredSessions = jdbc.query(
            """
            UPDATE waiter_call
            SET status = 'EXPIRED'
            WHERE status IN ('OPEN', 'ACKNOWLEDGED') AND expires_at <= :now
            RETURNING session_id
            """.trimIndent(),
            mapOf("now" to java.sql.Timestamp.from(now)),
        ) { rs, _ -> rs.getObject("session_id", UUID::class.java) }
        expiredSessions.forEach { sessionId ->
            appendWaiterEvent(restaurantId(sessionId), sessionId, DomainEventType.WAITER_UPDATED)
        }
        return expiredSessions.size
    }

    private fun transition(id: UUID, restaurantId: UUID, expected: String, target: String): Map<String, Any> {
        val now = clock.instant()
        val expiredSessionId = jdbc.query(
            """
            UPDATE waiter_call wc
            SET status = 'EXPIRED'
            FROM table_session ts
            WHERE ts.id = wc.session_id AND ts.restaurant_id = :restaurantId
              AND wc.status IN ('OPEN', 'ACKNOWLEDGED') AND wc.expires_at <= :now
              AND wc.id = :id
            RETURNING wc.session_id
            """.trimIndent(),
            mapOf("id" to id, "restaurantId" to restaurantId, "now" to java.sql.Timestamp.from(now)),
        ) { rs, _ -> rs.getObject("session_id", UUID::class.java) }.firstOrNull()
        expiredSessionId?.let {
            appendWaiterEvent(restaurantId, it, DomainEventType.WAITER_UPDATED)
        }
        val call = jdbc.query(
            """
            SELECT wc.id, wc.session_id, wc.reason, wc.text, wc.status, wc.created_at,
                   wc.acknowledged_at, wc.resolved_at, dt.label AS table_label
            FROM waiter_call wc
            JOIN table_session ts ON ts.id = wc.session_id
            JOIN dining_table dt ON dt.id = ts.table_id
            WHERE wc.id = :id AND ts.restaurant_id = :restaurantId
            FOR UPDATE OF wc
            """.trimIndent(),
            mapOf("id" to id, "restaurantId" to restaurantId),
        ) { rs, _ -> rs.toCallRecord() }.firstOrNull()
            ?: throw DomainException(ErrorCode.WAITER_CALL_NOT_FOUND, "Waiter call not found")
        if (call.status != expected) {
            throw DomainException(ErrorCode.STATE_CONFLICT, "Waiter call state does not allow this transition")
        }
        val changed = jdbc.update(
            """
            UPDATE waiter_call
            SET status = :target,
                acknowledged_at = CASE WHEN :target = 'ACKNOWLEDGED' THEN :now ELSE acknowledged_at END,
                resolved_at = CASE WHEN :target = 'RESOLVED' THEN :now ELSE resolved_at END
            WHERE id = :id AND status = :expected
            """.trimIndent(),
            mapOf(
                "id" to id,
                "expected" to expected,
                "target" to target,
                "now" to java.sql.Timestamp.from(now),
            ),
        )
        if (changed == 0) {
            throw DomainException(ErrorCode.STATE_CONFLICT, "Waiter call state changed concurrently")
        }
        appendWaiterEvent(restaurantId(call.sessionId), call.sessionId, DomainEventType.WAITER_UPDATED)
        return staffWaiterCallMap(call.copy(
            status = target,
            acknowledgedAt = if (target == "ACKNOWLEDGED") now else call.acknowledgedAt,
            resolvedAt = if (target == "RESOLVED") now else call.resolvedAt,
        ))
    }

    private fun guestAccess(authorization: String?, lockSession: Boolean = false): GuestAccess {
        val token = authorization
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.takeIf(String::isNotBlank)
            ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "Guest token is required")
        val tokenHash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
        )
        val lock = if (lockSession) "FOR UPDATE OF ts" else ""
        val guest = jdbc.query(
            """
            SELECT ts.id AS session_id, ts.restaurant_id, ts.status, ts.closed_at,
                   sp.id AS participant_id, sp.revoked_at
            FROM table_session ts
            JOIN session_participant sp ON sp.session_id = ts.id
            WHERE sp.token_hash = :tokenHash
            $lock
            """.trimIndent(),
            mapOf("tokenHash" to tokenHash),
        ) { rs, _ ->
            GuestAccess(
                sessionId = rs.getObject("session_id", UUID::class.java),
                participantId = rs.getObject("participant_id", UUID::class.java),
                restaurantId = rs.getObject("restaurant_id", UUID::class.java),
                sessionStatus = rs.getString("status"),
                closedAt = rs.getTimestamp("closed_at")?.toInstant(),
                revoked = rs.getTimestamp("revoked_at") != null,
            )
        }.firstOrNull() ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "Invalid guest token")
        if (guest.revoked) {
            throw DomainException(ErrorCode.SESSION_ACCESS_REVOKED, "Guest access was revoked")
        }
        if (guest.sessionStatus == "CLOSED" &&
            (guest.closedAt == null || guest.closedAt.isBefore(clock.instant().minus(CLOSED_READ_WINDOW)))
        ) {
            throw DomainException(ErrorCode.SESSION_CLOSED, "Session is no longer available")
        }
        return guest
    }

    private fun sessionCalls(sessionId: UUID): List<CallRecord> = jdbc.query(
        """
        SELECT wc.id, wc.session_id, wc.reason, wc.text, wc.status, wc.created_at,
               wc.acknowledged_at, wc.resolved_at, NULL::TEXT AS table_label
        FROM waiter_call wc
        WHERE wc.session_id = :sessionId
        ORDER BY wc.created_at, wc.id
        """.trimIndent(),
        mapOf("sessionId" to sessionId),
    ) { rs, _ -> rs.toCallRecord() }

    private fun expireSessionCalls(sessionId: UUID, now: Instant) {
        val expiredSessions = jdbc.query(
            """
            UPDATE waiter_call
            SET status = 'EXPIRED'
            WHERE session_id = :sessionId AND status IN ('OPEN', 'ACKNOWLEDGED')
              AND expires_at <= :now
            RETURNING session_id
            """.trimIndent(),
            mapOf("sessionId" to sessionId, "now" to java.sql.Timestamp.from(now)),
        ) { rs, _ -> rs.getObject("session_id", UUID::class.java) }
        expiredSessions.forEach { appendWaiterEvent(restaurantId(it), it, DomainEventType.WAITER_UPDATED) }
    }

    private fun expireSessions(sessionIds: List<UUID>, now: Instant) {
        val expiredSessions = jdbc.query(
            """
            UPDATE waiter_call
            SET status = 'EXPIRED'
            WHERE session_id IN (:sessionIds) AND status IN ('OPEN', 'ACKNOWLEDGED')
              AND expires_at <= :now
            RETURNING session_id
            """.trimIndent(),
            mapOf("sessionIds" to sessionIds, "now" to java.sql.Timestamp.from(now)),
        ) { rs, _ -> rs.getObject("session_id", UUID::class.java) }
        expiredSessions.forEach { appendWaiterEvent(restaurantId(it), it, DomainEventType.WAITER_UPDATED) }
    }

    private fun expireRestaurantCalls(restaurantId: UUID, now: Instant) {
        val expiredSessions = jdbc.query(
            """
            UPDATE waiter_call wc
            SET status = 'EXPIRED'
            FROM table_session ts
            WHERE ts.id = wc.session_id AND ts.restaurant_id = :restaurantId
              AND wc.status IN ('OPEN', 'ACKNOWLEDGED') AND wc.expires_at <= :now
            RETURNING wc.session_id
            """.trimIndent(),
            mapOf("restaurantId" to restaurantId, "now" to java.sql.Timestamp.from(now)),
        ) { rs, _ -> rs.getObject("session_id", UUID::class.java) }
        expiredSessions.forEach { appendWaiterEvent(restaurantId, it, DomainEventType.WAITER_UPDATED) }
    }

    private fun restaurantId(sessionId: UUID): UUID =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT restaurant_id FROM table_session WHERE id = :sessionId",
                mapOf("sessionId" to sessionId),
                UUID::class.java,
            ),
        )

    // Publica el cambio en la misma transacción para que un replay SSE no dependa de una notificación efímera.
    private fun appendWaiterEvent(restaurantId: UUID, sessionId: UUID, type: DomainEventType) {
        eventOutbox.append(
            DomainEvent(
                restaurantId = restaurantId,
                type = type,
                aggregateType = EventAggregateType.SESSION,
                aggregateId = sessionId,
                version = EventVersion.Increment,
                sessionId = sessionId,
            ),
        )
    }

    private fun cooldownSeconds(now: Instant, resolvedAt: Instant, cooldownSeconds: Int): Long {
        val remaining = Duration.between(now, resolvedAt.plusSeconds(cooldownSeconds.toLong()))
        return if (remaining.isNegative || remaining.isZero) 0 else remaining.seconds + if (remaining.nano > 0) 1 else 0
    }

    private fun waiterCallMap(call: CallRecord): Map<String, Any> =
        linkedMapOf<String, Any>(
            "id" to call.id.toString(),
            "reason" to call.reason,
            "status" to call.status,
            "createdAt" to call.createdAt.toString(),
        ).apply {
            call.text?.let { put("text", it) }
            call.acknowledgedAt?.let { put("acknowledgedAt", it.toString()) }
            call.resolvedAt?.let { put("resolvedAt", it.toString()) }
        }

    private fun staffWaiterCallMap(call: CallRecord): Map<String, Any> =
        waiterCallMap(call).toMutableMap().apply {
            put("sessionId", call.sessionId.toString())
            put("tableLabel", requireNotNull(call.tableLabel))
        }

    private fun java.sql.ResultSet.toCallRecord(): CallRecord = CallRecord(
        id = getObject("id", UUID::class.java),
        sessionId = getObject("session_id", UUID::class.java),
        reason = getString("reason"),
        text = getString("text"),
        status = getString("status"),
        createdAt = getTimestamp("created_at").toInstant(),
        acknowledgedAt = getTimestamp("acknowledged_at")?.toInstant(),
        resolvedAt = getTimestamp("resolved_at")?.toInstant(),
        tableLabel = getString("table_label"),
    )

    private companion object {
        val REASONS = setOf("BILL", "WATER", "HELP", "OTHER")
        val STATUSES = setOf("OPEN", "ACKNOWLEDGED", "RESOLVED", "EXPIRED")
        val CALL_TTL: Duration = Duration.ofMinutes(30)
        val CLOSED_READ_WINDOW: Duration = Duration.ofHours(24)
    }
}
