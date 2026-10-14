package com.somphie.preparation.application

import com.somphie.events.DomainEvent
import com.somphie.events.DomainEventType
import com.somphie.events.EventAggregateType
import com.somphie.events.EventOutboxService
import com.somphie.events.EventVersion
import com.somphie.orders.domain.Order
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class ItemAction { START, READY, DELIVER, CANCEL, UNDO, DELIVER_READY }

data class PreparationAccess(
    val restaurantId: UUID,
    val userId: UUID,
    val displayName: String,
    val roles: Set<String>,
    val permissions: Set<String>,
    val stationIds: Set<UUID>,
)

data class LockedPreparationOrder(
    val order: Order,
    val sessionOpen: Boolean,
    val sessionId: UUID,
    val itemStationIds: Set<UUID>,
)

interface PreparationActionRepository {
    fun lockForAction(restaurantId: UUID, itemId: UUID): LockedPreparationOrder?
    fun lockOrderForAction(restaurantId: UUID, orderId: UUID): LockedPreparationOrder?
    fun save(
        restaurantId: UUID,
        order: Order,
        expectedVersion: Long,
        cancelledItemId: UUID?,
        cancellationReason: String?,
        activityAt: Instant,
    ): Boolean
    fun staffOrder(access: PreparationAccess, orderId: UUID): Map<String, Any>
    fun undoWindowMinutes(restaurantId: UUID): Long
    fun recordAudit(
        access: PreparationAccess,
        itemId: UUID,
        action: ItemAction,
        beforeStatus: String,
        afterStatus: String,
        reason: String?,
    )
}

interface CourtesyCancellationPort {
    fun voidCourtesyForCancelledItem(
        restaurantId: UUID,
        itemId: UUID,
        actorId: UUID,
        reason: String,
        now: Instant,
    )
}

@Service
class GetStaffPreparationOrder(
    private val repository: PreparationActionRepository,
) {
    fun execute(access: PreparationAccess, orderId: UUID): Map<String, Any> {
        if ("order.read" !in access.permissions) {
            throw DomainException(ErrorCode.FORBIDDEN, "Staff permission is required")
        }
        return repository.staffOrder(access, orderId)
    }
}

@Service
class ApplyItemAction(
    private val repository: PreparationActionRepository,
    private val courtesyCancellation: CourtesyCancellationPort,
    private val clock: Clock,
    private val eventOutbox: EventOutboxService,
) {
    @Transactional
    fun execute(
        access: PreparationAccess,
        itemId: UUID,
        action: ItemAction,
        reason: String? = null,
    ): Map<String, Any> {
        val permission = when (action) {
            ItemAction.START, ItemAction.READY -> "item.prepare"
            ItemAction.DELIVER, ItemAction.DELIVER_READY -> "item.deliver"
            ItemAction.CANCEL -> "order.cancel"
            ItemAction.UNDO -> "item.undo"
        }

        if (permission !in access.permissions) throw DomainException(ErrorCode.FORBIDDEN, "Staff permission is required")
        if (action == ItemAction.CANCEL && reason.isNullOrBlank()) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Cancellation reason is required")
        }
        if (reason != null && reason.length > 140) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Cancellation reason is too long")
        }

        return apply(access, repository.lockForAction(access.restaurantId, itemId), itemId, action, reason)
    }

    @Transactional
    fun deliverReady(access: PreparationAccess, orderId: UUID): Map<String, Any> {
        if ("item.deliver" !in access.permissions) {
            throw DomainException(ErrorCode.FORBIDDEN, "Staff permission is required")
        }
        return apply(access, repository.lockOrderForAction(access.restaurantId, orderId), null, ItemAction.DELIVER_READY, null)
    }

    private fun apply(
        access: PreparationAccess,
        locked: LockedPreparationOrder?,
        itemId: UUID?,
        action: ItemAction,
        reason: String?,
    ): Map<String, Any> {
        val locked = locked
            ?: throw DomainException(ErrorCode.ITEM_NOT_FOUND, "Order item not found")
        if (!locked.sessionOpen) throw DomainException(ErrorCode.SESSION_CLOSED, "Session is closed")
        val target = itemId?.let { id -> locked.order.items.firstOrNull { it.id == id } }
        if (itemId != null && target == null) throw DomainException(ErrorCode.ITEM_NOT_FOUND, "Order item not found")
        if (action in setOf(ItemAction.START, ItemAction.READY) ||
            action == ItemAction.CANCEL && access.roles.any { it == "BAR" || it == "KITCHEN" }
        ) {
            if ("OWNER" !in access.roles && target!!.snapshot.stationId !in access.stationIds) {
                throw DomainException(ErrorCode.ITEM_STATION_FORBIDDEN, "Station is not assigned to this account")
            }
        }

        val now = clock.instant()
        val updated = when (action) {
            ItemAction.START -> locked.order.startItem(checkNotNull(itemId), now)
            ItemAction.READY -> locked.order.markItemReady(checkNotNull(itemId), now)
            ItemAction.DELIVER -> locked.order.deliverItem(checkNotNull(itemId), now)
            ItemAction.CANCEL -> locked.order.cancelItem(checkNotNull(itemId), now)
            ItemAction.UNDO -> locked.order.undoItem(
                checkNotNull(itemId),
                now,
                Duration.ofMinutes(repository.undoWindowMinutes(access.restaurantId)),
            )
            ItemAction.DELIVER_READY -> locked.order.deliverReadyItems(now)
        }
        val changed = updated.version != locked.order.version
        if (changed &&
            !repository.save(
                access.restaurantId,
                updated,
                locked.order.version,
                if (action == ItemAction.CANCEL) itemId else null,
                reason,
                now,
            )
        ) {
            throw DomainException(ErrorCode.ORDER_ALREADY_DECIDED, "Order was concurrently modified")
        }
        if (changed && action == ItemAction.CANCEL) {
            courtesyCancellation.voidCourtesyForCancelledItem(
                access.restaurantId,
                checkNotNull(itemId),
                access.userId,
                checkNotNull(reason),
                now,
            )
        }
        val beforeById = locked.order.items.associateBy { it.id }
        updated.items.filter { beforeById[it.id]?.status != it.status }.forEach { item ->
            repository.recordAudit(
                access, item.id, action, beforeById.getValue(item.id).status.name, item.status.name,
                if (action == ItemAction.CANCEL) reason else null,
            )
        }
        if (changed) {
            // Eventos y transición persisten juntos; el replay idempotente no duplica publicaciones.
            updated.items.filter { beforeById[it.id]?.status != it.status }.forEach { item ->
                eventOutbox.append(
                    DomainEvent(
                        restaurantId = access.restaurantId,
                        type = DomainEventType.ITEM_UPDATED,
                        aggregateType = EventAggregateType.ITEM,
                        aggregateId = item.id,
                        version = EventVersion.Increment,
                        sessionId = locked.sessionId,
                        stationId = item.snapshot.stationId,
                    ),
                )
            }
            eventOutbox.append(
                DomainEvent(
                    restaurantId = access.restaurantId,
                    type = DomainEventType.ORDER_UPDATED,
                    aggregateType = EventAggregateType.ORDER,
                    aggregateId = updated.id,
                    version = EventVersion.Persisted(updated.version),
                    sessionId = locked.sessionId,
                ),
            )
        }
        return repository.staffOrder(access, updated.id)
    }
}
