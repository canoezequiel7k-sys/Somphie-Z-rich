package com.somphie.orders.infrastructure

import com.somphie.orders.application.BillableOrderLine
import com.somphie.orders.application.CourtesyOrderItem
import com.somphie.orders.application.OrderOptionSnapshot
import com.somphie.orders.application.OrderChargesPort
import com.somphie.orders.application.PendingOrderSummary
import com.somphie.orders.application.ReceiptOrderLine
import com.somphie.orders.application.ReceiptOrderOption
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Repository
class JdbcOrderChargesAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : OrderChargesPort {
    override fun billableLines(sessionId: UUID, restaurantId: UUID): List<BillableOrderLine> =
        jdbc.query(
            """
            SELECT o.display_code, oi.product_name, oi.quantity, oi.unit_price_minor, oi.line_total_minor
            FROM customer_order o
            JOIN order_item oi ON oi.order_id = o.id
            WHERE o.session_id = :sessionId
              AND o.restaurant_id = :restaurantId
              AND o.status IN ('ACCEPTED', 'IN_PREPARATION', 'READY', 'DELIVERED')
              AND oi.status <> 'CANCELLED'
            ORDER BY o.created_at, o.id, oi.id
            """.trimIndent(),
            mapOf("sessionId" to sessionId, "restaurantId" to restaurantId),
        ) { rs, _ ->
            BillableOrderLine(
                orderCode = rs.getString("display_code"),
                productName = rs.getString("product_name"),
                quantity = rs.getInt("quantity"),
                unitPriceMinor = rs.getLong("unit_price_minor"),
                lineTotalMinor = rs.getLong("line_total_minor"),
            )
        }

    override fun pendingOrders(sessionId: UUID, restaurantId: UUID): List<PendingOrderSummary> =
        jdbc.query(
            """
            SELECT display_code, total_minor
            FROM customer_order
            WHERE session_id = :sessionId
              AND restaurant_id = :restaurantId
              AND status = 'PENDING_REVIEW'
            ORDER BY created_at, id
            """.trimIndent(),
            mapOf("sessionId" to sessionId, "restaurantId" to restaurantId),
        ) { rs, _ ->
            PendingOrderSummary(
                orderCode = rs.getString("display_code"),
                totalMinor = rs.getLong("total_minor"),
            )
        }

    override fun receiptLines(sessionId: UUID, restaurantId: UUID): List<ReceiptOrderLine> =
        jdbc.query(
            """
            SELECT o.display_code, oi.product_name, oi.quantity, oi.base_price_minor,
                   oi.unit_price_minor, oi.line_total_minor, oi.options_snapshot::text
            FROM customer_order o
            JOIN order_item oi ON oi.order_id = o.id
            WHERE o.session_id = :sessionId
              AND o.restaurant_id = :restaurantId
              AND o.status IN ('ACCEPTED', 'IN_PREPARATION', 'READY', 'DELIVERED')
              AND oi.status <> 'CANCELLED'
            ORDER BY o.created_at, o.id, oi.id
            """.trimIndent(),
            mapOf("sessionId" to sessionId, "restaurantId" to restaurantId),
        ) { rs, _ ->
            val options = objectMapper.readValue(
                rs.getString("options_snapshot") ?: "[]",
                OPTION_SNAPSHOT_TYPE,
            ).map {
                ReceiptOrderOption(it.groupName, it.optionName, it.priceDeltaMinor)
            }
            ReceiptOrderLine(
                orderCode = rs.getString("display_code"),
                productName = rs.getString("product_name"),
                quantity = rs.getInt("quantity"),
                basePriceMinor = rs.getLong("base_price_minor"),
                unitPriceMinor = rs.getLong("unit_price_minor"),
                lineTotalMinor = rs.getLong("line_total_minor"),
                options = options,
            )
        }

    override fun billableLinesBySession(
        sessionIds: List<UUID>,
        restaurantId: UUID,
    ): Map<UUID, List<BillableOrderLine>> {
        if (sessionIds.isEmpty()) return emptyMap()
        return jdbc.query(
            """
            SELECT o.session_id, o.display_code, oi.product_name, oi.quantity,
                   oi.unit_price_minor, oi.line_total_minor
            FROM customer_order o
            JOIN order_item oi ON oi.order_id = o.id
            WHERE o.session_id IN (:sessionIds)
              AND o.restaurant_id = :restaurantId
              AND o.status IN ('ACCEPTED', 'IN_PREPARATION', 'READY', 'DELIVERED')
              AND oi.status <> 'CANCELLED'
            ORDER BY o.session_id, o.created_at, o.id, oi.id
            """.trimIndent(),
            mapOf("sessionIds" to sessionIds, "restaurantId" to restaurantId),
        ) { rs, _ ->
            rs.getObject("session_id", UUID::class.java) to BillableOrderLine(
                orderCode = rs.getString("display_code"),
                productName = rs.getString("product_name"),
                quantity = rs.getInt("quantity"),
                unitPriceMinor = rs.getLong("unit_price_minor"),
                lineTotalMinor = rs.getLong("line_total_minor"),
            )
        }.groupBy({ it.first }, { it.second })
    }

    override fun courtesyItems(sessionId: UUID, restaurantId: UUID): List<CourtesyOrderItem> =
        jdbc.query(
            """
            SELECT oi.id, oi.line_total_minor, oi.status
            FROM order_item oi
            JOIN customer_order o ON o.id = oi.order_id
            WHERE o.session_id = :sessionId AND o.restaurant_id = :restaurantId
            ORDER BY oi.id
            """.trimIndent(),
            mapOf("sessionId" to sessionId, "restaurantId" to restaurantId),
        ) { rs, _ ->
            val status = rs.getString("status")
            CourtesyOrderItem(
                id = rs.getObject("id", UUID::class.java),
                lineTotalMinor = rs.getLong("line_total_minor"),
                accepted = status in setOf("IN_PREPARATION", "READY", "DELIVERED"),
                cancelled = status == "CANCELLED",
            )
        }

    private companion object {
        val OPTION_SNAPSHOT_TYPE = object : TypeReference<List<OrderOptionSnapshot>>() {}
    }
}
