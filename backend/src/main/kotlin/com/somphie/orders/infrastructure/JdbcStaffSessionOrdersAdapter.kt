package com.somphie.orders.infrastructure

import com.somphie.floor.application.StaffSessionOrder
import com.somphie.floor.application.StaffSessionOrderActivity
import com.somphie.floor.application.StaffSessionOrderItem
import com.somphie.floor.application.StaffSessionOrderOption
import com.somphie.floor.application.StaffSessionOrdersPort
import com.somphie.floor.application.SessionClosureOrderActivity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Repository
class JdbcStaffSessionOrdersAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : StaffSessionOrdersPort {
    override fun closureActivity(sessionId: UUID, restaurantId: UUID): SessionClosureOrderActivity {
        val parameters = mapOf("sessionId" to sessionId, "restaurantId" to restaurantId)
        val pendingOrderIds = jdbc.query(
            """
            SELECT id FROM customer_order
            WHERE session_id = :sessionId AND restaurant_id = :restaurantId AND status = 'PENDING_REVIEW'
            ORDER BY id
            """.trimIndent(),
            parameters,
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }
        val activeItemIds = jdbc.query(
            """
            SELECT oi.id
            FROM order_item oi
            JOIN customer_order o ON o.id = oi.order_id
            WHERE o.session_id = :sessionId
              AND o.restaurant_id = :restaurantId
              AND oi.status IN ('QUEUED', 'IN_PREPARATION', 'READY')
            ORDER BY oi.id
            """.trimIndent(),
            parameters,
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }
        return SessionClosureOrderActivity(pendingOrderIds, activeItemIds)
    }

    override fun activityBySession(
        sessionIds: List<UUID>,
        restaurantId: UUID,
    ): Map<UUID, StaffSessionOrderActivity> {
        if (sessionIds.isEmpty()) return emptyMap()
        return jdbc.query(
            """
            SELECT o.session_id,
                   COUNT(DISTINCT o.id) FILTER (WHERE o.status = 'PENDING_REVIEW') AS pending_orders_count,
                   COALESCE(BOOL_OR(oi.status IN ('QUEUED', 'IN_PREPARATION', 'READY')), FALSE) AS has_items_in_service
            FROM customer_order o
            LEFT JOIN order_item oi ON oi.order_id = o.id
            WHERE o.session_id IN (:sessionIds) AND o.restaurant_id = :restaurantId
            GROUP BY o.session_id
            """.trimIndent(),
            mapOf("sessionIds" to sessionIds, "restaurantId" to restaurantId),
        ) { rs, _ ->
            rs.getObject("session_id", UUID::class.java) to StaffSessionOrderActivity(
                pendingOrdersCount = rs.getInt("pending_orders_count"),
                hasItemsInService = rs.getBoolean("has_items_in_service"),
            )
        }.toMap()
    }

    override fun ordersForSession(sessionId: UUID, restaurantId: UUID): List<StaffSessionOrder> {
        val orders = jdbc.query(
            """
            SELECT o.id, o.display_code, o.status, o.version, o.total_minor, o.note,
                   o.created_at, o.decided_at, o.decided_by, o.rejection_reason, o.rejection_text,
                   p.id AS participant_id, p.display_name AS participant_name,
                   creator.id AS creator_user_id, creator.display_name AS creator_name,
                   u.display_name AS decider_name
            FROM customer_order o
            JOIN table_session ts ON ts.id = o.session_id
            JOIN dining_table dt ON dt.id = ts.table_id AND dt.restaurant_id = o.restaurant_id
            LEFT JOIN session_participant p ON p.id = o.participant_id
            LEFT JOIN app_user creator ON creator.id = o.created_by_user_id
            LEFT JOIN app_user u ON u.id = o.decided_by
            WHERE o.session_id = :sessionId AND o.restaurant_id = :restaurantId
            ORDER BY o.created_at, o.id
            """.trimIndent(),
            mapOf("sessionId" to sessionId, "restaurantId" to restaurantId),
        ) { rs, _ ->
            StaffSessionOrder(
                id = rs.getObject("id", UUID::class.java),
                sessionId = sessionId,
                displayCode = rs.getString("display_code"),
                status = rs.getString("status"),
                version = rs.getLong("version"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                participantId = rs.getObject("participant_id", UUID::class.java),
                participantName = rs.getString("participant_name"),
                creatorUserId = rs.getObject("creator_user_id", UUID::class.java),
                creatorName = rs.getString("creator_name"),
                decidedAt = rs.getTimestamp("decided_at")?.toInstant(),
                deciderId = rs.getObject("decided_by", UUID::class.java),
                deciderName = rs.getString("decider_name"),
                note = rs.getString("note"),
                rejectionReason = rs.getString("rejection_reason"),
                rejectionText = rs.getString("rejection_text"),
                totalMinor = rs.getLong("total_minor"),
                items = emptyList(),
            )
        }
        if (orders.isEmpty()) return emptyList()

        val items = jdbc.query(
            """
            SELECT oi.order_id, oi.id, oi.product_id, oi.product_name, oi.product_name_i18n::text,
                   s.code AS station_code, oi.quantity, oi.note, oi.base_price_minor,
                   oi.options_delta_minor, oi.unit_price_minor, oi.line_total_minor, oi.status,
                   oi.queued_at, oi.started_at, oi.ready_at, oi.delivered_at, oi.cancelled_at,
                   oi.options_snapshot::text,
                   (p.available AND p.active AND c.active) AS product_currently_available
            FROM order_item oi
            JOIN customer_order o ON o.id = oi.order_id
            JOIN station s ON s.id = oi.station_id
            JOIN product p ON p.id = oi.product_id
            JOIN subcategory sub ON sub.id = p.subcategory_id
            JOIN category c ON c.id = sub.category_id
            WHERE oi.order_id IN (:orderIds) AND o.restaurant_id = :restaurantId
            ORDER BY oi.order_id, oi.id
            """.trimIndent(),
            mapOf("orderIds" to orders.map { it.id }, "restaurantId" to restaurantId),
        ) { rs, _ ->
            val options = objectMapper.readValue(rs.getString("options_snapshot") ?: "[]", optionListType)
                .map { option ->
                    StaffSessionOrderOption(
                        groupId = UUID.fromString(option.getValue("groupId").toString()),
                        optionId = UUID.fromString(option.getValue("optionId").toString()),
                        groupName = option.getValue("groupName").toString(),
                        optionName = option.getValue("optionName").toString(),
                        priceDeltaMinor = (option.getValue("priceDeltaMinor") as Number).toLong(),
                    )
                }
            val item = StaffSessionOrderItem(
                id = rs.getObject("id", UUID::class.java),
                productId = rs.getObject("product_id", UUID::class.java),
                productName = rs.getString("product_name"),
                productNameI18n = objectMapper.readValue(
                    rs.getString("product_name_i18n") ?: "{}",
                    stringMapType,
                ),
                basePriceMinor = rs.getLong("base_price_minor"),
                optionsDeltaMinor = rs.getLong("options_delta_minor"),
                unitPriceMinor = rs.getLong("unit_price_minor"),
                quantity = rs.getInt("quantity"),
                lineTotalMinor = rs.getLong("line_total_minor"),
                station = rs.getString("station_code"),
                status = rs.getString("status"),
                note = rs.getString("note"),
                queuedAt = rs.getTimestamp("queued_at")?.toInstant(),
                startedAt = rs.getTimestamp("started_at")?.toInstant(),
                readyAt = rs.getTimestamp("ready_at")?.toInstant(),
                deliveredAt = rs.getTimestamp("delivered_at")?.toInstant(),
                cancelledAt = rs.getTimestamp("cancelled_at")?.toInstant(),
                options = options,
                productCurrentlyAvailable = rs.getBoolean("product_currently_available"),
            )
            rs.getObject("order_id", UUID::class.java) to item
        }
        val itemsByOrder = items.groupBy({ it.first }, { it.second })
        return orders.map { order -> order.copy(items = itemsByOrder[order.id].orEmpty()) }
    }

    private companion object {
        val optionListType = object : TypeReference<List<Map<String, Any>>>() {}
        val stringMapType = object : TypeReference<Map<String, String>>() {}
    }
}
