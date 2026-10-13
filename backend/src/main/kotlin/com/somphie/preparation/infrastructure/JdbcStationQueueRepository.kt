package com.somphie.preparation.infrastructure

import com.somphie.preparation.application.StationQueueCursor
import com.somphie.preparation.application.StationListEntry
import com.somphie.preparation.application.StationQueueRecord
import com.somphie.preparation.application.StationQueueRepository
import com.somphie.preparation.application.QueueOption
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.util.UUID

@Repository
class JdbcStationQueueRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
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

    override fun findStations(restaurantId: UUID, assignedStationIds: Set<UUID>?): List<StationListEntry> {
        if (assignedStationIds != null && assignedStationIds.isEmpty()) return emptyList()
        val assignmentFilter = if (assignedStationIds == null) "" else "AND id IN (:stationIds)"
        val params = mutableMapOf<String, Any>("restaurantId" to restaurantId)
        if (assignedStationIds != null) params["stationIds"] = assignedStationIds
        return jdbc.query(
            """
            SELECT id, code, name FROM station
            WHERE restaurant_id = :restaurantId $assignmentFilter
            ORDER BY code
            """.trimIndent(),
            params,
        ) { rs, _ ->
            StationListEntry(
                rs.getObject("id", UUID::class.java),
                rs.getString("code"),
                rs.getString("name"),
            )
        }
    }

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
        val cursorFilter = when {
            after == null -> ""
            after.acceptedAt == null -> {
                params["cursorItemId"] = after.itemId
                "AND o.accepted_at IS NULL AND oi.id > :cursorItemId"
            }
            else -> {
                params["cursorAcceptedAt"] = Timestamp.from(after.acceptedAt)
                params["cursorItemId"] = after.itemId
                """
                AND (
                    o.accepted_at IS NULL
                    OR o.accepted_at > :cursorAcceptedAt
                    OR (o.accepted_at = :cursorAcceptedAt AND oi.id > :cursorItemId)
                )
                """.trimIndent()
            }
        }
        // accepted_at define FIFO; el ID solo desempata y estabiliza históricos sin referencia temporal.
        return jdbc.query(
            """
            SELECT oi.id AS item_id, oi.order_id, o.display_code, dt.label AS table_label,
                   oi.product_name, oi.quantity, oi.note, oi.status, o.accepted_at, oi.queued_at,
                   oi.options_snapshot::text AS options_snapshot,
                   rs.delayed_item_sla_min
            FROM order_item oi
            JOIN customer_order o ON o.id = oi.order_id
            JOIN table_session ts ON ts.id = o.session_id
            JOIN dining_table dt ON dt.id = ts.table_id
            JOIN restaurant_settings rs ON rs.restaurant_id = o.restaurant_id
            WHERE o.restaurant_id = :restaurantId
              AND oi.station_id = :stationId
              AND o.status IN ('ACCEPTED', 'IN_PREPARATION', 'READY')
              AND ts.status = 'OPEN'
              AND oi.status IN (:statuses)
              $cursorFilter
            ORDER BY o.accepted_at ASC NULLS LAST, oi.id ASC
            LIMIT :limit
            """.trimIndent(),
            params,
        ) { rs, _ ->
            val options = objectMapper.readValue(rs.getString("options_snapshot") ?: "[]", optionListType).map {
                QueueOption(
                    java.util.UUID.fromString(it.getValue("groupId").toString()),
                    java.util.UUID.fromString(it.getValue("optionId").toString()),
                    it.getValue("groupName").toString(),
                    it.getValue("optionName").toString(),
                    (it.getValue("priceDeltaMinor") as Number).toLong(),
                )
            }
            StationQueueRecord(
                itemId = rs.getObject("item_id", UUID::class.java),
                orderId = rs.getObject("order_id", UUID::class.java),
                orderCode = rs.getString("display_code"),
                tableLabel = rs.getString("table_label"),
                productName = rs.getString("product_name"),
                quantity = rs.getInt("quantity"),
                note = rs.getString("note"),
                status = rs.getString("status"),
                acceptedAt = rs.getTimestamp("accepted_at")?.toInstant(),
                queuedAt = rs.getTimestamp("queued_at")?.toInstant(),
                delayedSlaMinutes = rs.getLong("delayed_item_sla_min"),
                options = options,
            )
        }
    }

    private companion object {
        val optionListType = object : TypeReference<List<Map<String, Any>>>() {}
    }
}
