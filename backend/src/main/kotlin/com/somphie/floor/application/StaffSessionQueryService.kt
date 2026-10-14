package com.somphie.floor.application

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class StaffSessionQueryService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val sessionBill: SessionBillPort,
    private val sessionOrders: StaffSessionOrdersPort,
    private val waiterCalls: WaiterCallService,
) {
    private data class StaffTableRow(
        val tableId: UUID,
        val label: String,
        val sessionId: UUID?,
        val openedAt: String?,
    )

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun getTables(restaurantId: UUID): Map<String, Any> {
        val tables = jdbc.query(
            """
            SELECT dt.id AS table_id, dt.label, ts.id AS session_id, ts.opened_at
            FROM dining_table dt
            LEFT JOIN table_session ts
              ON ts.table_id = dt.id AND ts.restaurant_id = dt.restaurant_id AND ts.status = 'OPEN'
            WHERE dt.restaurant_id = :restaurantId
            ORDER BY dt.label, dt.id
            """.trimIndent(),
            mapOf("restaurantId" to restaurantId),
        ) { rs, _ ->
            StaffTableRow(
                tableId = rs.getObject("table_id", UUID::class.java),
                label = rs.getString("label"),
                sessionId = rs.getObject("session_id", UUID::class.java),
                openedAt = rs.getTimestamp("opened_at")?.toInstant()?.toString(),
            )
        }
        val sessionIds = tables.mapNotNull { it.sessionId }.distinct()
        val bills = sessionBill.billSummariesForSessions(sessionIds, restaurantId)
        val activeSessionIds = bills.keys.toList()
        val orderActivity = sessionOrders.activityBySession(activeSessionIds, restaurantId)
        val activeWaiterCalls = waiterCalls.activeSessionIds(activeSessionIds, restaurantId)

        return mapOf(
            "items" to tables.map { table ->
                val sessionId = table.sessionId?.takeIf(bills::containsKey)
                val bill = sessionId?.let(bills::get)
                val activity = sessionId?.let(orderActivity::get)
                val pendingOrdersCount = activity?.pendingOrdersCount ?: 0
                val hasWaiterCall = sessionId != null && sessionId in activeWaiterCalls
                val displayStatus = when {
                    sessionId == null -> "FREE"
                    pendingOrdersCount > 0 -> "PENDING_ORDERS"
                    activity?.hasItemsInService == true -> "IN_SERVICE"
                    bill != null && bill.balanceMinor < 0 -> "OCCUPIED"
                    bill?.chargesMinor == 0L -> "READY_TO_CLOSE"
                    bill?.balanceMinor == 0L -> "READY_TO_CLOSE"
                    bill != null && bill.balanceMinor > 0 -> "AWAITING_PAYMENT"
                    // El contrato no define un estado propio para saldo acreedor; se conserva el estado base y se expone el saldo firmado.
                    else -> "OCCUPIED"
                }
                linkedMapOf<String, Any>(
                    "tableId" to table.tableId.toString(),
                    "label" to table.label,
                    "displayStatus" to displayStatus,
                    "pendingOrdersCount" to pendingOrdersCount,
                    "waiterCallActive" to hasWaiterCall,
                ).apply {
                    sessionId?.let {
                        put("sessionId", it.toString())
                        put("openedAt", requireNotNull(table.openedAt))
                        put("balanceMinor", requireNotNull(bill).balanceMinor)
                    }
                }
            },
        )
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun getSession(sessionId: UUID, restaurantId: UUID): Map<String, Any> {
        // El lock mantiene alineados pedidos y cuenta con los escritores de la sesión.
        val session = jdbc.query(
            """
            SELECT ts.id, ts.table_id, ts.status, ts.opened_at, ts.closed_at, ts.close_reason, dt.label
            FROM table_session ts
            JOIN dining_table dt ON dt.id = ts.table_id
            WHERE ts.id = :sessionId
              AND ts.restaurant_id = :restaurantId
              AND dt.restaurant_id = :restaurantId
            FOR UPDATE OF ts
            """.trimIndent(),
            mapOf("sessionId" to sessionId, "restaurantId" to restaurantId),
        ) { rs, _ ->
            linkedMapOf<String, Any>(
                "id" to rs.getObject("id", UUID::class.java).toString(),
                "tableId" to rs.getObject("table_id", UUID::class.java).toString(),
                "status" to rs.getString("status"),
                "tableLabel" to rs.getString("label"),
                "openedAt" to rs.getTimestamp("opened_at").toInstant().toString(),
                "readOnly" to (rs.getString("status") == "CLOSED"),
            ).apply {
                rs.getTimestamp("closed_at")?.toInstant()?.toString()?.let { put("closedAt", it) }
                rs.getString("close_reason")?.let { put("closeReason", it) }
            }
        }.firstOrNull() ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Session not found")

        val participants = jdbc.query(
            """
            SELECT id, display_name, role, revoked_at
            FROM session_participant
            WHERE session_id = :sessionId
            ORDER BY joined_at, id
            """.trimIndent(),
            mapOf("sessionId" to sessionId),
        ) { rs, _ ->
            mapOf(
                "id" to rs.getObject("id", UUID::class.java).toString(),
                "displayName" to rs.getString("display_name"),
                "role" to rs.getString("role"),
                "revoked" to (rs.getTimestamp("revoked_at") != null),
            )
        }
        val orders = sessionOrders.ordersForSession(sessionId, restaurantId).map {
            orderResponse(it, session.getValue("tableLabel").toString())
        }

        return mapOf(
            "session" to session,
            "participants" to participants,
            "orders" to orders,
            "bill" to sessionBill.billForSession(sessionId, restaurantId),
        )
    }

    private fun orderResponse(order: StaffSessionOrder, tableLabel: String): Map<String, Any> =
        linkedMapOf<String, Any>(
            "id" to order.id.toString(),
            "sessionId" to order.sessionId.toString(),
            "tableLabel" to tableLabel,
            "displayCode" to order.displayCode,
            "status" to order.status,
            "version" to order.version,
            "createdAt" to order.createdAt.toString(),
            "createdBy" to if (order.creatorUserId != null) {
                mapOf("userId" to order.creatorUserId.toString(), "displayName" to order.creatorName.orEmpty())
            } else {
                mapOf("participantId" to order.participantId.toString(), "displayName" to order.participantName.orEmpty())
            },
            "items" to order.items.map(::itemResponse),
            "totalMinor" to order.totalMinor,
        ).apply {
            if (order.participantId != null && order.creatorUserId != null) {
                put(
                    "participant",
                    mapOf("participantId" to order.participantId.toString(), "displayName" to order.participantName.orEmpty()),
                )
            }
            order.decidedAt?.let { put("decidedAt", it.toString()) }
            if (order.deciderId != null) {
                put("decidedBy", mapOf("userId" to order.deciderId.toString(), "displayName" to order.deciderName.orEmpty()))
            }
            order.note?.let { put("note", it) }
            order.rejectionReason?.let {
                put("rejection", linkedMapOf("reasonCode" to it).apply {
                    order.rejectionText?.let { text -> put("reasonText", text) }
                })
            }
        }

    private fun itemResponse(item: StaffSessionOrderItem): Map<String, Any> =
        linkedMapOf<String, Any>(
            "id" to item.id.toString(),
            "productId" to item.productId.toString(),
            "productName" to item.productName,
            "productNameI18n" to item.productNameI18n,
            "basePriceMinor" to item.basePriceMinor,
            "optionsDeltaMinor" to item.optionsDeltaMinor,
            "unitPriceMinor" to item.unitPriceMinor,
            "quantity" to item.quantity,
            "lineTotalMinor" to item.lineTotalMinor,
            "station" to item.station,
            "status" to item.status,
            "options" to item.options.map { option ->
                mapOf(
                    "groupId" to option.groupId.toString(),
                    "optionId" to option.optionId.toString(),
                    "groupName" to option.groupName,
                    "optionName" to option.optionName,
                    "priceDeltaMinor" to option.priceDeltaMinor,
                )
            },
            "productCurrentlyAvailable" to item.productCurrentlyAvailable,
        ).apply {
            item.note?.let { put("note", it) }
            item.queuedAt?.let { put("queuedAt", it.toString()) }
            item.startedAt?.let { put("startedAt", it.toString()) }
            item.readyAt?.let { put("readyAt", it.toString()) }
            item.deliveredAt?.let { put("deliveredAt", it.toString()) }
            item.cancelledAt?.let { put("cancelledAt", it.toString()) }
        }

}
