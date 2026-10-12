package com.somphie.orders.domain

/**
 * Estado de un pedido. Fuente de verdad: `docs/02-dominio/estados-y-transiciones.md` §2.
 *
 * Hasta `ACCEPTED` las transiciones son decisiones sobre el pedido. Desde `ACCEPTED` el estado es
 * DERIVADO de sus ítems ([OrderStatusDerivation], §4).
 */
enum class OrderStatus {
    PENDING_REVIEW,
    ACCEPTED,
    REJECTED,
    CANCELLED,
    EXPIRED,
    IN_PREPARATION,
    READY,
    DELIVERED,
}

/**
 * Deriva el estado de un pedido ya aceptado a partir de los estados de sus ítems
 * (`estados-y-transiciones.md` §4). Es una función PURA: no consulta reloj ni base de datos.
 *
 * Esto resuelve el problema del plan original: nadie puede marcar "terminado" un pedido completo
 * mientras queden ítems sin terminar en otra estación.
 */
object OrderStatusDerivation {

    /**
     * Sea S los ítems NO cancelados:
     * - S vacío → `CANCELLED`
     * - todos `DELIVERED` → `DELIVERED`
     * - todos `READY` o `DELIVERED` → `READY`
     * - alguno `IN_PREPARATION`, `READY` o `DELIVERED` → `IN_PREPARATION`
     * - todos `QUEUED` → `ACCEPTED`
     *
     * Un ítem `PENDING` no puede existir en un pedido aceptado: es un error de programación
     * (no un error de negocio), por eso lanza [IllegalArgumentException].
     */
    fun fromItems(items: Collection<ItemStatus>): OrderStatus {
        val active = items.filter { it != ItemStatus.CANCELLED }
        require(ItemStatus.PENDING !in active) { "An accepted order cannot have PENDING items" }
        return when {
            active.isEmpty() -> OrderStatus.CANCELLED
            active.all { it == ItemStatus.DELIVERED } -> OrderStatus.DELIVERED
            active.all { it == ItemStatus.READY || it == ItemStatus.DELIVERED } -> OrderStatus.READY
            active.any {
                it == ItemStatus.IN_PREPARATION || it == ItemStatus.READY || it == ItemStatus.DELIVERED
            } -> OrderStatus.IN_PREPARATION
            else -> OrderStatus.ACCEPTED
        }
    }
}
