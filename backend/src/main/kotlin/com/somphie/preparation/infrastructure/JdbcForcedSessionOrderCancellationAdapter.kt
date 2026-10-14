package com.somphie.preparation.infrastructure

import com.somphie.audit.AuditService
import com.somphie.floor.application.ForcedSessionOrderCancellationPort
import com.somphie.orders.domain.ItemStatus
import com.somphie.orders.domain.OrderStatus
import com.somphie.preparation.application.CourtesyCancellationPort
import com.somphie.preparation.application.PreparationActionRepository
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Repository
class JdbcForcedSessionOrderCancellationAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val preparation: PreparationActionRepository,
    private val courtesyCancellation: CourtesyCancellationPort,
    private val audit: AuditService,
) : ForcedSessionOrderCancellationPort {
    @Transactional
    override fun cancelOpenWork(
        sessionId: UUID,
        restaurantId: UUID,
        actorId: UUID,
        reason: String,
        now: Instant,
    ) {
        val orderIds = jdbc.query(
            """
            SELECT id FROM customer_order
            WHERE session_id = :sessionId AND restaurant_id = :restaurantId
              AND status IN ('PENDING_REVIEW', 'ACCEPTED', 'IN_PREPARATION', 'READY')
            ORDER BY id
            """.trimIndent(),
            mapOf("sessionId" to sessionId, "restaurantId" to restaurantId),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }
        orderIds.forEach { orderId ->
            val locked = preparation.lockOrderForAction(restaurantId, orderId)
                ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found")
            if (!locked.sessionOpen) throw DomainException(ErrorCode.SESSION_CLOSED, "Session is closed")
            val original = locked.order
            if (original.status == OrderStatus.PENDING_REVIEW) {
                val cancelled = original.cancel(now)
                if (!preparation.save(restaurantId, cancelled, original.version, null, null, now)) {
                    throw DomainException(ErrorCode.ORDER_ALREADY_DECIDED, "Order was concurrently modified")
                }
                audit.record(
                    restaurantId = restaurantId.toString(),
                    actorType = "USER",
                    actorId = actorId.toString(),
                    action = "ORDER_CANCELLED",
                    targetType = "ORDER",
                    targetId = orderId.toString(),
                )
                return@forEach
            }

            var current = original
            original.items.filter { it.status in ACTIVE_ITEM_STATUSES }.forEach { item ->
                val before = current
                current = current.cancelItem(item.id, now)
                if (!preparation.save(restaurantId, current, before.version, item.id, reason, now)) {
                    throw DomainException(ErrorCode.ORDER_ALREADY_DECIDED, "Order was concurrently modified")
                }
                courtesyCancellation.voidCourtesyForCancelledItem(
                    restaurantId, item.id, actorId, reason, now,
                )
                audit.record(
                    restaurantId = restaurantId.toString(),
                    actorType = "USER",
                    actorId = actorId.toString(),
                    action = "ITEM_CANCELLED",
                    targetType = "ORDER_ITEM",
                    targetId = item.id.toString(),
                    reason = reason,
                    beforeSummary = """{"status":"${item.status.name}"}""",
                    afterSummary = """{"status":"CANCELLED"}""",
                )
            }
        }
    }

    private companion object {
        val ACTIVE_ITEM_STATUSES = setOf(ItemStatus.QUEUED, ItemStatus.IN_PREPARATION, ItemStatus.READY)
    }
}
