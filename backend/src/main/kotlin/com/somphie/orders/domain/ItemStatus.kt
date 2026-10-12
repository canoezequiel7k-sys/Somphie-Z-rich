package com.somphie.orders.domain

/**
 * Estado de un ítem de pedido. Fuente de verdad: `docs/02-dominio/estados-y-transiciones.md` §3.
 * Los nombres viajan por la API: no se renombran sin tocar el contrato y los clientes.
 */
enum class ItemStatus {
    PENDING,
    QUEUED,
    IN_PREPARATION,
    READY,
    DELIVERED,
    CANCELLED,
}
