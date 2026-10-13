package com.somphie.orders.domain

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.domain.Money
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Ítem de un pedido. Inmutable: cada transición devuelve un ítem nuevo o lanza [DomainException]
 * SIN modificar nada (regla "un error no cambia datos").
 *
 * Solo las transiciones de `estados-y-transiciones.md` §3 son válidas; cualquier otra combinación
 * lanza `ITEM_INVALID_TRANSITION` (§7). El reloj NO se consulta aquí: la capa de aplicación inyecta
 * `now` (regla de oro 8 de `backend.md`).
 *
 * El snapshot (nombre, estación, precios y opciones, RN-ORD-04) vive en [ItemSnapshot]; el ítem lo
 * conserva sin cambios en todas sus transiciones.
 */
class OrderItem private constructor(
    val id: UUID,
    val snapshot: ItemSnapshot,
    val status: ItemStatus,
    val queuedAt: Instant?,
    val startedAt: Instant?,
    val readyAt: Instant?,
    val deliveredAt: Instant?,
    val cancelledAt: Instant?,
) {

    /** Importe de la línea con el PRECIO HISTÓRICO del snapshot (RN-BIL-01). */
    val lineTotal: Money get() = snapshot.lineTotal

    /** El pedido fue aceptado: `PENDING → QUEUED`. */
    fun queue(now: Instant): OrderItem {
        requireFrom(ItemStatus.PENDING)
        return copyWith(status = ItemStatus.QUEUED, queuedAt = now)
    }

    /**
     * Se excluyó al aceptar el pedido (RN-ORD-10): `PENDING → CANCELLED`. También es la transición que
     * sufren los ítems cuando el pedido se rechaza, se cancela o expira (estados §3).
     */
    fun exclude(now: Instant): OrderItem {
        requireFrom(ItemStatus.PENDING)
        return copyWith(status = ItemStatus.CANCELLED, cancelledAt = now)
    }

    /** `QUEUED → IN_PREPARATION`. */
    fun start(now: Instant): OrderItem {
        requireFrom(ItemStatus.QUEUED)
        return copyWith(status = ItemStatus.IN_PREPARATION, startedAt = now)
    }

    /** `QUEUED` o `IN_PREPARATION` → `READY` (el atajo desde `QUEUED` está permitido). */
    fun markReady(now: Instant): OrderItem {
        requireFrom(ItemStatus.QUEUED, ItemStatus.IN_PREPARATION)
        return copyWith(status = ItemStatus.READY, readyAt = now)
    }

    /** `READY → DELIVERED`. */
    fun deliver(now: Instant): OrderItem {
        requireFrom(ItemStatus.READY)
        return copyWith(status = ItemStatus.DELIVERED, deliveredAt = now)
    }

    /**
     * Cancela un ítem aceptado (RN-PRE-07). Un ítem `DELIVERED` no se cancela (`ITEM_NOT_CANCELLABLE`).
     * El motivo obligatorio lo valida y audita la capa de aplicación.
     */
    fun cancel(now: Instant): OrderItem {
        if (status == ItemStatus.DELIVERED) {
            throw DomainException(
                ErrorCode.ITEM_NOT_CANCELLABLE,
                "Item $id is already delivered",
                mapOf("currentStatus" to status),
            )
        }
        requireFrom(ItemStatus.QUEUED, ItemStatus.IN_PREPARATION, ItemStatus.READY)
        return copyWith(status = ItemStatus.CANCELLED, cancelledAt = now)
    }

    /**
     * Revierte un paso (RN-PRE-09). Deshacer una entrega solo vale dentro de [undoWindow]
     * (`deliveredUndoWindowMin`): pasado ese tiempo, `ITEM_UNDO_WINDOW_EXPIRED`.
     * Al volver atrás se borra solo la marca de tiempo del estado que se abandona. Si `READY`
     * vino directamente de `QUEUED`, `startedAt` permanece nulo: no se inventa un inicio.
     */
    fun undo(now: Instant, undoWindow: Duration): OrderItem = when (status) {
        ItemStatus.IN_PREPARATION -> copyWith(status = ItemStatus.QUEUED, startedAt = null)
        ItemStatus.READY -> copyWith(status = ItemStatus.IN_PREPARATION, readyAt = null)
        ItemStatus.DELIVERED -> {
            val deliveredAt = checkNotNull(deliveredAt) { "Delivered item $id has no deliveredAt" }
            if (Duration.between(deliveredAt, now) > undoWindow) {
                throw DomainException(
                    ErrorCode.ITEM_UNDO_WINDOW_EXPIRED,
                    "Item $id can no longer be undone",
                    mapOf("currentStatus" to status),
                )
            }
            copyWith(status = ItemStatus.READY, deliveredAt = null)
        }
        else -> throw invalidTransition()
    }

    private fun requireFrom(vararg allowed: ItemStatus) {
        if (status !in allowed) throw invalidTransition()
    }

    private fun invalidTransition() = DomainException(
        ErrorCode.ITEM_INVALID_TRANSITION,
        "Item $id cannot perform this action from $status",
        mapOf("currentStatus" to status),
    )

    private fun copyWith(
        status: ItemStatus,
        queuedAt: Instant? = this.queuedAt,
        startedAt: Instant? = this.startedAt,
        readyAt: Instant? = this.readyAt,
        deliveredAt: Instant? = this.deliveredAt,
        cancelledAt: Instant? = this.cancelledAt,
    ) = OrderItem(id, snapshot, status, queuedAt, startedAt, readyAt, deliveredAt, cancelledAt)

    companion object {
        /** Un ítem recién creado junto con su pedido (RN-ORD-05): nace `PENDING`, con su snapshot. */
        fun pending(id: UUID, snapshot: ItemSnapshot): OrderItem =
            OrderItem(id, snapshot, ItemStatus.PENDING, null, null, null, null, null)

        fun restore(
            id: UUID,
            snapshot: ItemSnapshot,
            status: ItemStatus,
            queuedAt: Instant?,
            startedAt: Instant?,
            readyAt: Instant?,
            deliveredAt: Instant?,
            cancelledAt: Instant?,
        ): OrderItem = OrderItem(id, snapshot, status, queuedAt, startedAt, readyAt, deliveredAt, cancelledAt)
    }
}
