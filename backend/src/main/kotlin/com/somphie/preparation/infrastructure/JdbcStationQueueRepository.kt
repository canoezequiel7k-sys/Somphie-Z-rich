package com.somphie.preparation.infrastructure

import com.somphie.preparation.application.StationQueueCursor
import com.somphie.preparation.application.StationQueueRecord
import com.somphie.preparation.application.StationQueueRepository
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.util.UUID

@Repository
class JdbcStationQueueRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : StationQueueRepository {
    override fun stationBelongsToRestaurant(stationId: UUID, restaurantId: UUID): Boolean =
        jdbc.query(
            """
            SELECT EXISTS (
                SELECT 1 FROM station
                WHERE id = :stationId AND restaurant_id = :restaurantId
            )
            """.trimIndent(),
            mapOf("stationId" to stationId, "restaurantId" to restaurantId),
        ) { rs, _ -> rs.getBoolean(1) }.first()

    override fun findQueueItems(
        restaurantId: UUID,
        stationId: UUID,
        statuses: List<String>,
        after: StationQueueCursor?,
        limit: Int,
    ): List<StationQueueRecord> {
        val params = mutableMapOf<String, Any>(
            "restaurantId" to restaurantId,
            "stationId" to stationId,
            "statuses" to statuses,
            "limit" to limit,
        )
        // Accepting an order queues all included items at accepted_at; item ID breaks equal-time ties.
        val cursorFilter = if (after == null) "" else {
            params["cursorAt"] = Timestamp.from(after.queuedAt)
            params["cursorItemId"] = after.itemId
            "AND (o.accepted_at, oi.id) > (:cursorAt, :cursorItemId)"
        }
        return jdbc.query(
            """
            SELECT oi.id AS item_id, oi.order_id, o.display_code, dt.label AS table_label,
                   oi.product_name, oi.quantity, oi.note, oi.status, o.accepted_at,
                   rs.delayed_item_sla_min
            FROM order_item oi
            JOIN customer_order o ON o.id = oi.order_id
            JOIN table_session ts ON ts.id = o.session_id
            JOIN dining_table dt ON dt.id = ts.table_id
            JOIN restaurant_settings rs ON rs.restaurant_id = o.restaurant_id
            WHERE o.restaurant_id = :restaurantId
              AND oi.station_id = :stationId
              AND o.status IN ('ACCEPTED', 'IN_PREPARATION', 'READY')
              AND oi.status IN (:statuses)
              AND o.accepted_at IS NOT NULL
              $cursorFilter
            ORDER BY o.accepted_at, oi.id
            LIMIT :limit
            """.trimIndent(),
            params,
        ) { rs, _ ->
            StationQueueRecord(
                itemId = rs.getObject("item_id", UUID::class.java),
                orderId = rs.getObject("order_id", UUID::class.java),
                orderCode = rs.getString("display_code"),
                tableLabel = rs.getString("table_label"),
                productName = rs.getString("product_name"),
                quantity = rs.getInt("quantity"),
                note = rs.getString("note"),
                status = rs.getString("status"),
                queuedAt = rs.getTimestamp("accepted_at").toInstant(),
                delayedSlaMinutes = rs.getLong("delayed_item_sla_min"),
            )
        }
    }
}
