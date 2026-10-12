package com.somphie.orders.domain

import com.somphie.shared.domain.Money
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Acciones de preparación que se prueban (solo existen en las pruebas: el dominio expone métodos). */
internal enum class ItemAction { START, MARK_READY, DELIVER, CANCEL, UNDO }

internal val T0: Instant = Instant.parse("2026-10-11T15:00:00Z")
internal val UNDO_WINDOW: Duration = Duration.ofMinutes(5)

/** Snapshot de ejemplo: un café de $ 4.850,00 (485000 centavos) por defecto. */
internal fun sampleSnapshot(
    quantity: Int = 1,
    basePrice: Long = 485_000,
    options: List<OptionSnapshot> = emptyList(),
) = ItemSnapshot(
    productId = UUID.randomUUID(),
    productName = "Café",
    stationId = UUID.randomUUID(),
    quantity = quantity,
    basePrice = Money.of(basePrice),
    options = options,
)

/** Atajo de las pruebas de ítems: un ítem `PENDING` con un snapshot cualquiera. */
internal fun OrderItem.Companion.pending(id: UUID): OrderItem = pending(id, sampleSnapshot())

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
