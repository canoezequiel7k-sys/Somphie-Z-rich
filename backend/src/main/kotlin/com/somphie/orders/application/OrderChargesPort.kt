package com.somphie.orders.application

import java.util.UUID

/** Snapshot de una línea que el módulo Orders ya determinó como facturable. */
data class BillableOrderLine(
    val orderCode: String,
    val productName: String,
    val quantity: Int,
    val unitPriceMinor: Long,
    val lineTotalMinor: Long,
)

/** Pedido pendiente que se informa al cliente, pero todavía no integra los cargos. */
data class PendingOrderSummary(
    val orderCode: String,
    val totalMinor: Long,
)

data class CourtesyOrderItem(
    val id: UUID,
    val lineTotalMinor: Long,
    val accepted: Boolean,
    val cancelled: Boolean,
)

/** Billing consume esta selección sin duplicar RN-BIL-01 ni depender del esquema de Orders. */
interface OrderChargesPort {
    fun billableLines(sessionId: UUID, restaurantId: UUID): List<BillableOrderLine>

    fun billableLinesBySession(sessionIds: List<UUID>, restaurantId: UUID): Map<UUID, List<BillableOrderLine>>

    fun pendingOrders(sessionId: UUID, restaurantId: UUID): List<PendingOrderSummary>

    fun courtesyItems(sessionId: UUID, restaurantId: UUID): List<CourtesyOrderItem>
}
