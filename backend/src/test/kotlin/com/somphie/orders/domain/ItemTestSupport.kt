package com.somphie.orders.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Acciones de preparación que se prueban (solo existen en las pruebas: el dominio expone métodos). */
internal enum class ItemAction { START, MARK_READY, DELIVER, CANCEL, UNDO }

internal val T0: Instant = Instant.parse("2026-10-11T15:00:00Z")
internal val UNDO_WINDOW: Duration = Duration.ofMinutes(5)

/** Arma un ítem en el estado pedido recorriendo transiciones válidas (nunca por constructor). */
internal fun itemIn(target: ItemStatus, now: Instant = T0): OrderItem {
    val pending = OrderItem.pending(UUID.randomUUID())
    return when (target) {
        ItemStatus.PENDING -> pending
        ItemStatus.QUEUED -> pending.queue(now)
        ItemStatus.IN_PREPARATION -> pending.queue(now).start(now)
        ItemStatus.READY -> pending.queue(now).start(now).markReady(now)
        ItemStatus.DELIVERED -> pending.queue(now).start(now).markReady(now).deliver(now)
        ItemStatus.CANCELLED -> pending.queue(now).cancel(now)
    }
}

internal fun OrderItem.act(action: ItemAction, now: Instant = T0): OrderItem = when (action) {
    ItemAction.START -> start(now)
    ItemAction.MARK_READY -> markReady(now)
    ItemAction.DELIVER -> deliver(now)
    ItemAction.CANCEL -> cancel(now)
    ItemAction.UNDO -> undo(now, UNDO_WINDOW)
}
