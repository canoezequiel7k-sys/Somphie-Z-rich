package com.somphie.floor.application

import com.somphie.audit.AuditService
import com.somphie.events.DomainEvent
import com.somphie.events.DomainEventType
import com.somphie.events.EventAggregateType
import com.somphie.events.EventOutboxService
import com.somphie.events.EventVersion
import com.somphie.floor.domain.JoinCode
import com.somphie.floor.domain.SessionClosureContext
import com.somphie.floor.domain.SessionCloseReason
import com.somphie.floor.domain.SessionParticipant
import com.somphie.floor.domain.SessionParticipantRole
import com.somphie.floor.domain.TableSession
import com.somphie.floor.domain.TableSessionStatus
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class StaffSessionClosureService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val sessionBill: SessionBillPort,
    private val sessionOrders: StaffSessionOrdersPort,
    private val forcedOrderCancellation: ForcedSessionOrderCancellationPort,
    private val forcedSessionRefund: ForcedSessionRefundPort,
    private val audit: AuditService,
    private val eventOutbox: EventOutboxService,
    private val clock: Clock,
) {
    private data class StoredSession(
        val id: UUID,
        val tableId: UUID,
        val tableLabel: String,
        val status: TableSessionStatus,
        val openedAt: Instant,
        val closedAt: Instant?,
        val closeReason: SessionCloseReason?,
        val joinCode: JoinCode?,
        val lastActivityAt: Instant,
    )

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun closeNormally(sessionId: UUID, restaurantId: UUID, userId: UUID): Map<String, Any> {
        val stored = jdbc.query(
            """
            SELECT ts.id, ts.table_id, dt.label, ts.status, ts.opened_at, ts.closed_at,
                   ts.close_reason, ts.join_code, ts.last_activity_at
            FROM table_session ts
            JOIN dining_table dt ON dt.id = ts.table_id AND dt.restaurant_id = ts.restaurant_id
            WHERE ts.id = :sessionId AND ts.restaurant_id = :restaurantId
            FOR UPDATE OF ts
            """.trimIndent(),
            mapOf("sessionId" to sessionId, "restaurantId" to restaurantId),
        ) { rs, _ ->
            StoredSession(
                id = rs.getObject("id", UUID::class.java),
                tableId = rs.getObject("table_id", UUID::class.java),
                tableLabel = rs.getString("label"),
                status = TableSessionStatus.valueOf(rs.getString("status")),
                openedAt = rs.getTimestamp("opened_at").toInstant(),
                closedAt = rs.getTimestamp("closed_at")?.toInstant(),
                closeReason = rs.getString("close_reason")?.let(SessionCloseReason::valueOf),
                joinCode = rs.getString("join_code")?.let(JoinCode::of),
                lastActivityAt = rs.getTimestamp("last_activity_at").toInstant(),
            )
        }.firstOrNull() ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Session not found")

        if (stored.status == TableSessionStatus.CLOSED) return stored.toResponse()

        val participants = jdbc.query(
            """
            SELECT id, display_name, role, user_id, revoked_at
            FROM session_participant
            WHERE session_id = :sessionId
            ORDER BY joined_at, id
            """.trimIndent(),
            mapOf("sessionId" to sessionId),
        ) { rs, _ ->
            SessionParticipant(
                id = rs.getObject("id", UUID::class.java),
                displayName = rs.getString("display_name"),
                role = SessionParticipantRole.valueOf(rs.getString("role")),
                userId = rs.getObject("user_id", UUID::class.java),
                revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
            )
        }
        val session = TableSession.fromStoredState(
            id = stored.id,
            tableId = stored.tableId,
            status = stored.status,
            openedAt = stored.openedAt,
            closedAt = stored.closedAt,
            closeReason = stored.closeReason,
            joinCode = stored.joinCode,
            participants = participants,
            lastActivityAt = stored.lastActivityAt,
        )

        // Billing toma el mismo lock y aporta el saldo derivado; Floor no lo recalcula.
        val bill = sessionBill.billSummariesForSessions(listOf(sessionId), restaurantId)[sessionId]
            ?: throw IllegalStateException("Open session was missing from the billing summary")
        val orderActivity = sessionOrders.closureActivity(sessionId, restaurantId)
        val closed = session.closeNormally(
            clock.instant(),
            SessionClosureContext(
                pendingOrderIds = orderActivity.pendingOrderIds,
                activeItemIds = orderActivity.activeItemIds,
                hasBillableCharges = bill.chargesMinor > 0,
                balanceMinor = bill.balanceMinor,
            ),
        )
        val closedAt = requireNotNull(closed.closedAt)
        val closeReason = requireNotNull(closed.closeReason)
        val updated = jdbc.update(
            """
            UPDATE table_session
            SET status = 'CLOSED', closed_at = :closedAt, close_reason = :closeReason, join_code = NULL
            WHERE id = :sessionId AND restaurant_id = :restaurantId AND status = 'OPEN'
            """.trimIndent(),
            mapOf(
                "closedAt" to java.sql.Timestamp.from(closedAt),
                "closeReason" to closeReason.name,
                "sessionId" to sessionId,
                "restaurantId" to restaurantId,
            ),
        )
        if (updated != 1) throw DomainException(ErrorCode.SESSION_CLOSED, "Session is no longer open")

        audit.record(
            restaurantId = restaurantId.toString(),
            actorType = "USER",
            actorId = userId.toString(),
            action = "SESSION_CLOSED",
            targetType = "SESSION",
            targetId = sessionId.toString(),
            beforeSummary = """{"status":"OPEN"}""",
            afterSummary = """{"status":"CLOSED","closeReason":"${closeReason.name}"}""",
        )
        appendSessionClosed(restaurantId, sessionId, stored.tableId)
        return stored.copy(status = closed.status, closedAt = closedAt, closeReason = closeReason, joinCode = null)
            .toResponse()
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun forceClose(
        sessionId: UUID,
        restaurantId: UUID,
        userId: UUID,
        idempotencyKey: String,
        reason: String,
    ): Map<String, Any> {
        if (reason.isBlank() || reason.length > 140) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "A reason between 1 and 140 characters is required")
        }
        val stored = jdbc.query(
            """
            SELECT ts.id, ts.table_id, dt.label, ts.status, ts.opened_at, ts.closed_at,
                   ts.close_reason, ts.join_code, ts.last_activity_at
            FROM table_session ts
            JOIN dining_table dt ON dt.id = ts.table_id AND dt.restaurant_id = ts.restaurant_id
            WHERE ts.id = :sessionId AND ts.restaurant_id = :restaurantId
            FOR UPDATE OF ts
            """.trimIndent(),
            mapOf("sessionId" to sessionId, "restaurantId" to restaurantId),
        ) { rs, _ ->
            StoredSession(
                id = rs.getObject("id", UUID::class.java),
                tableId = rs.getObject("table_id", UUID::class.java),
                tableLabel = rs.getString("label"),
                status = TableSessionStatus.valueOf(rs.getString("status")),
                openedAt = rs.getTimestamp("opened_at").toInstant(),
                closedAt = rs.getTimestamp("closed_at")?.toInstant(),
                closeReason = rs.getString("close_reason")?.let(SessionCloseReason::valueOf),
                joinCode = rs.getString("join_code")?.let(JoinCode::of),
                lastActivityAt = rs.getTimestamp("last_activity_at").toInstant(),
            )
        }.firstOrNull() ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Session not found")

        if (stored.status == TableSessionStatus.CLOSED) return stored.toResponse()

        val participants = jdbc.query(
            """
            SELECT id, display_name, role, user_id, revoked_at
            FROM session_participant WHERE session_id = :sessionId ORDER BY joined_at, id
            """.trimIndent(),
            mapOf("sessionId" to sessionId),
        ) { rs, _ ->
            SessionParticipant(
                id = rs.getObject("id", UUID::class.java),
                displayName = rs.getString("display_name"),
                role = SessionParticipantRole.valueOf(rs.getString("role")),
                userId = rs.getObject("user_id", UUID::class.java),
                revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
            )
        }
        val session = TableSession.fromStoredState(
            id = stored.id,
            tableId = stored.tableId,
            status = stored.status,
            openedAt = stored.openedAt,
            closedAt = stored.closedAt,
            closeReason = stored.closeReason,
            joinCode = stored.joinCode,
            participants = participants,
            lastActivityAt = stored.lastActivityAt,
        )
        val now = clock.instant()
        forcedOrderCancellation.cancelOpenWork(sessionId, restaurantId, userId, reason, now)
        val balanceMinor = forcedSessionRefund.refundNegativeBalance(
            sessionId, restaurantId, userId, reason, idempotencyKey,
        )
        val closed = session.forceClose(now, reason)
        val closedAt = requireNotNull(closed.closedAt)
        val closeReason = requireNotNull(closed.closeReason)
        val updated = jdbc.update(
            """
            UPDATE table_session
            SET status = 'CLOSED', closed_at = :closedAt, close_reason = :closeReason, join_code = NULL
            WHERE id = :sessionId AND restaurant_id = :restaurantId AND status = 'OPEN'
            """.trimIndent(),
            mapOf(
                "closedAt" to java.sql.Timestamp.from(closedAt),
                "closeReason" to closeReason.name,
                "sessionId" to sessionId,
                "restaurantId" to restaurantId,
            ),
        )
        if (updated != 1) throw DomainException(ErrorCode.SESSION_CLOSED, "Session is no longer open")
        val afterSummary = if (balanceMinor > 0) {
            """{"status":"CLOSED","closeReason":"FORCED","uncollectedBalanceMinor":$balanceMinor}"""
        } else {
            """{"status":"CLOSED","closeReason":"FORCED"}"""
        }
        audit.record(
            restaurantId = restaurantId.toString(),
            actorType = "USER",
            actorId = userId.toString(),
            action = "SESSION_FORCE_CLOSED",
            targetType = "SESSION",
            targetId = sessionId.toString(),
            reason = reason,
            beforeSummary = """{"status":"OPEN"}""",
            afterSummary = afterSummary,
        )
        appendSessionClosed(restaurantId, sessionId, stored.tableId)
        return stored.copy(status = closed.status, closedAt = closedAt, closeReason = closeReason, joinCode = null)
            .toResponse()
    }

    private fun appendSessionClosed(restaurantId: UUID, sessionId: UUID, tableId: UUID) {
        eventOutbox.append(
            DomainEvent(
                restaurantId = restaurantId,
                type = DomainEventType.SESSION_CLOSED,
                aggregateType = EventAggregateType.SESSION,
                aggregateId = sessionId,
                version = EventVersion.Increment,
                sessionId = sessionId,
            ),
        )
        eventOutbox.append(
            DomainEvent(
                restaurantId = restaurantId,
                type = DomainEventType.TABLE_UPDATED,
                aggregateType = EventAggregateType.TABLE,
                aggregateId = tableId,
                version = EventVersion.Increment,
                sessionId = sessionId,
            ),
        )
    }

    private fun StoredSession.toResponse(): Map<String, Any> =
        linkedMapOf(
            "id" to id.toString(),
            "status" to status.name,
            "tableLabel" to tableLabel,
            "openedAt" to openedAt.toString(),
            "readOnly" to (status == TableSessionStatus.CLOSED),
            "tableId" to tableId.toString(),
        ).apply {
            closedAt?.let { put("closedAt", it.toString()) }
            closeReason?.let { put("closeReason", it.name) }
        }
}
