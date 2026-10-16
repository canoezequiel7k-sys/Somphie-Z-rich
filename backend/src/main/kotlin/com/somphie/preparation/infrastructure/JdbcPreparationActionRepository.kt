package com.somphie.preparation.infrastructure

import com.somphie.orders.domain.ItemSnapshot
import com.somphie.orders.domain.ItemStatus
import com.somphie.orders.domain.OptionSnapshot
import com.somphie.orders.domain.Order
import com.somphie.orders.domain.OrderItem
import com.somphie.orders.domain.OrderStatus
import com.somphie.orders.domain.Rejection
import com.somphie.orders.domain.RejectionReason
import com.somphie.identity.domain.StaffReadScope
import com.somphie.preparation.application.LockedPreparationOrder
import com.somphie.preparation.application.PreparationActionRepository
import com.somphie.preparation.application.PreparationAccess
import com.somphie.preparation.application.ItemAction
import com.somphie.audit.AuditService
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.domain.Money
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcPreparationActionRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val auditService: AuditService,
) : PreparationActionRepository {
    private data class LockedOrderRow(
        val status: String,
        val createdAt: java.time.Instant,
        val acceptedAt: java.time.Instant?,
        val decidedAt: java.time.Instant?,
        val rejectionReason: String?,
        val rejectionText: String?,
        val version: Long,
    )

    override fun lockForAction(restaurantId: UUID, itemId: UUID): LockedPreparationOrder? {
        val location = jdbc.query(
            """
            SELECT o.id, o.session_id
            FROM order_item oi JOIN customer_order o ON o.id = oi.order_id
            WHERE oi.id = :itemId AND o.restaurant_id = :restaurantId
            """.trimIndent(),
            mapOf("itemId" to itemId, "restaurantId" to restaurantId),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getObject("session_id", UUID::class.java) }
            .firstOrNull() ?: return null
        return loadLockedOrder(restaurantId, location.first, location.second)
    }

    override fun lockOrderForAction(restaurantId: UUID, orderId: UUID): LockedPreparationOrder? {
        val sessionId = jdbc.query(
            "SELECT session_id FROM customer_order WHERE id = :orderId AND restaurant_id = :restaurantId",
            mapOf("orderId" to orderId, "restaurantId" to restaurantId),
        ) { rs, _ -> rs.getObject("session_id", UUID::class.java) }.firstOrNull() ?: return null
        return loadLockedOrder(restaurantId, orderId, sessionId)
    }

    private fun loadLockedOrder(restaurantId: UUID, orderId: UUID, sessionId: UUID): LockedPreparationOrder? {
        val sessionOpen = jdbc.query(
            "SELECT status FROM table_session WHERE id = :sessionId AND restaurant_id = :restaurantId FOR UPDATE",
            mapOf("sessionId" to sessionId, "restaurantId" to restaurantId),
        ) { rs, _ -> rs.getString(1) == "OPEN" }.firstOrNull() ?: return null
        val orderRow = jdbc.query(
            """
            SELECT id, status, created_at, accepted_at, decided_at, rejection_reason, rejection_text, version
            FROM customer_order
            WHERE id = :orderId AND restaurant_id = :restaurantId
            FOR UPDATE
            """.trimIndent(),
            mapOf("orderId" to orderId, "restaurantId" to restaurantId),
        ) { rs, _ ->
            LockedOrderRow(
                rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("accepted_at")?.toInstant(), rs.getTimestamp("decided_at")?.toInstant(),
                rs.getString("rejection_reason"), rs.getString("rejection_text"), rs.getLong("version"),
            )
        }.firstOrNull() ?: return null
        val allStationsBelongToRestaurant = jdbc.query(
            """
            SELECT NOT EXISTS (
                SELECT 1 FROM order_item oi
                JOIN station s ON s.id = oi.station_id
                WHERE oi.order_id = :orderId AND s.restaurant_id <> :restaurantId
            )
            """.trimIndent(),
            mapOf("orderId" to orderId, "restaurantId" to restaurantId),
        ) { rs, _ -> rs.getBoolean(1) }.firstOrNull() ?: false
        if (!allStationsBelongToRestaurant) {
            throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found")
        }

        val items = jdbc.query(
            """
            SELECT id, product_id, product_name, product_name_i18n::text AS product_name_i18n,
                   station_id, quantity, note, base_price_minor, status, queued_at, started_at,
                   ready_at, delivered_at, cancelled_at, options_snapshot::text AS options_snapshot
            FROM order_item WHERE order_id = :orderId ORDER BY id FOR UPDATE
            """.trimIndent(),
            mapOf("orderId" to orderId),
        ) { rs, _ ->
            val optionsJson = rs.getString("options_snapshot") ?: "[]"
            val options = objectMapper.readValue(optionsJson, optionListType).map {
                OptionSnapshot(
                    UUID.fromString(it.getValue("groupId").toString()),
                    UUID.fromString(it.getValue("optionId").toString()),
                    it.getValue("groupName").toString(),
                    it.getValue("optionName").toString(),
                    Money.of((it.getValue("priceDeltaMinor") as Number).toLong()),
                )
            }
            val names = objectMapper.readValue(rs.getString("product_name_i18n") ?: "{}", mapType)
                .mapValues { it.value.toString() }
            val itemSnapshot = ItemSnapshot(
                productId = rs.getObject("product_id", UUID::class.java),
                productName = rs.getString("product_name"),
                productNameI18n = names,
                stationId = rs.getObject("station_id", UUID::class.java),
                quantity = rs.getInt("quantity"),
                note = rs.getString("note"),
                basePrice = Money.of(rs.getLong("base_price_minor")),
                options = options,
            )
            OrderItem.restore(
                id = rs.getObject("id", UUID::class.java),
                snapshot = itemSnapshot,
                status = ItemStatus.valueOf(rs.getString("status")),
                queuedAt = rs.getTimestamp("queued_at")?.toInstant(),
                startedAt = rs.getTimestamp("started_at")?.toInstant(),
                readyAt = rs.getTimestamp("ready_at")?.toInstant(),
                deliveredAt = rs.getTimestamp("delivered_at")?.toInstant(),
                cancelledAt = rs.getTimestamp("cancelled_at")?.toInstant(),
            )
        }
        val status = OrderStatus.valueOf(orderRow.status)
        val reason = orderRow.rejectionReason?.let { Rejection(RejectionReason.valueOf(it), orderRow.rejectionText) }
        val order = Order.restore(
            id = orderId,
            status = status,
            items = items,
            createdAt = orderRow.createdAt,
            acceptedAt = orderRow.acceptedAt,
            decidedAt = orderRow.decidedAt,
            rejection = reason,
            version = orderRow.version,
        )
        return LockedPreparationOrder(order, sessionOpen, sessionId, items.map { it.snapshot.stationId }.toSet())
    }

    override fun save(
        restaurantId: UUID,
        order: Order,
        expectedVersion: Long,
        cancelledItemId: UUID?,
        cancellationReason: String?,
        activityAt: Instant,
    ): Boolean {
        val changed = jdbc.update(
            """
            UPDATE customer_order
            SET status = :status, version = :newVersion
            WHERE id = :id AND restaurant_id = :restaurantId AND version = :expectedVersion
            """.trimIndent(),
            mapOf(
                "status" to order.status.name,
                "newVersion" to order.version,
                "id" to order.id,
                "restaurantId" to restaurantId,
                "expectedVersion" to expectedVersion,
            ),
        )
        if (changed != 1) return false
        val sessionActivityUpdated = jdbc.update(
            """
            UPDATE table_session
            SET last_activity_at = :activityAt
            WHERE id = (
                SELECT session_id FROM customer_order
                WHERE id = :orderId AND restaurant_id = :restaurantId
            ) AND status = 'OPEN'
            """.trimIndent(),
            mapOf(
                "activityAt" to Timestamp.from(activityAt),
                "orderId" to order.id,
                "restaurantId" to restaurantId,
            ),
        )
        if (sessionActivityUpdated != 1) {
            throw DomainException(ErrorCode.SESSION_CLOSED, "Session is closed")
        }
        order.items.forEach { item ->
            val updated = jdbc.update(
                """
                UPDATE order_item
                SET status = :status, queued_at = :queuedAt, started_at = :startedAt,
                    ready_at = :readyAt, delivered_at = :deliveredAt, cancelled_at = :cancelledAt,
                    cancellation_reason = CASE WHEN id = :cancelledItemId THEN :reason ELSE cancellation_reason END
                WHERE id = :id AND order_id = :orderId AND EXISTS (
                    SELECT 1 FROM customer_order o
                    WHERE o.id = :orderId AND o.restaurant_id = :restaurantId
                )
                """.trimIndent(),
                mapOf(
                    "status" to item.status.name,
                    "queuedAt" to item.queuedAt?.let(Timestamp::from),
                    "startedAt" to item.startedAt?.let(Timestamp::from),
                    "readyAt" to item.readyAt?.let(Timestamp::from),
                    "deliveredAt" to item.deliveredAt?.let(Timestamp::from),
                    "cancelledAt" to item.cancelledAt?.let(Timestamp::from),
                    "cancelledItemId" to cancelledItemId,
                    "reason" to cancellationReason,
                    "id" to item.id,
                    "orderId" to order.id,
                    "restaurantId" to restaurantId,
                ),
            )
            if (updated != 1) throw DomainException(ErrorCode.ITEM_NOT_FOUND, "Order item disappeared during update")
        }
        return true
    }

    override fun staffOrder(access: PreparationAccess, orderId: UUID): Map<String, Any> {
        val isKitchen = StaffReadScope.isKitchenOnly(access.roles)
        if (isKitchen && access.stationIds.isEmpty()) {
            throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found")
        }
        val stationFilter = if (isKitchen) {
            """
            AND EXISTS (
                SELECT 1 FROM order_item scoped
                WHERE scoped.order_id = o.id
                  AND scoped.station_id IN (:stationIds)
                  AND scoped.status IN ('QUEUED', 'IN_PREPARATION', 'READY', 'DELIVERED')
            )
            """.trimIndent()
        } else {
            ""
        }
        val itemStationFilter = if (isKitchen) {
            "AND oi.station_id IN (:stationIds) AND oi.status IN ('QUEUED', 'IN_PREPARATION', 'READY', 'DELIVERED')"
        } else {
            ""
        }
        val orderParams = mutableMapOf<String, Any>("orderId" to orderId, "restaurantId" to access.restaurantId)
        if (isKitchen) orderParams["stationIds"] = access.stationIds
        val orderRow = jdbc.query(
            """
            SELECT o.id, o.session_id, o.status, o.display_code, o.total_minor, o.note, o.created_at,
                   o.version, o.decided_at, o.decided_by, p.id AS participant_id,
                   p.display_name AS participant_name, creator.id AS creator_user_id,
                   creator.display_name AS creator_name, u.display_name AS decider_name,
                   dt.label AS table_label, o.rejection_reason, o.rejection_text
            FROM customer_order o
            JOIN table_session ts ON ts.id = o.session_id
            JOIN dining_table dt ON dt.id = ts.table_id
            LEFT JOIN session_participant p ON p.id = o.participant_id
            LEFT JOIN app_user creator ON creator.id = o.created_by_user_id
            LEFT JOIN app_user u ON u.id = o.decided_by
            WHERE o.id = :orderId AND o.restaurant_id = :restaurantId
              $stationFilter
            """.trimIndent(),
            orderParams,
        ) { rs, _ ->
            linkedMapOf<String, Any>(
                "id" to rs.getObject("id", UUID::class.java).toString(),
                "sessionId" to rs.getObject("session_id", UUID::class.java).toString(),
                "tableLabel" to rs.getString("table_label"),
                "status" to rs.getString("status"),
                "displayCode" to rs.getString("display_code"),
                "version" to rs.getLong("version"),
                "createdAt" to rs.getTimestamp("created_at").toInstant().toString(),
            ).apply {
                if (!isKitchen) {
                    put("totalMinor", rs.getLong("total_minor"))
                    if (rs.getObject("creator_user_id", UUID::class.java) != null) {
                        put("createdBy", mapOf(
                            "userId" to rs.getString("creator_user_id"),
                            "displayName" to rs.getString("creator_name"),
                        ))
                        rs.getString("participant_id")?.let {
                            put("participant", mapOf("participantId" to it, "displayName" to rs.getString("participant_name")))
                        }
                    } else {
                        put("createdBy", mapOf("participantId" to rs.getString("participant_id"), "displayName" to rs.getString("participant_name")))
                    }
                }
                rs.getTimestamp("decided_at")?.toInstant()?.toString()?.let { put("decidedAt", it) }
                rs.getObject("decided_by", UUID::class.java)?.let {
                    put("decidedBy", mapOf("userId" to it.toString(), "displayName" to rs.getString("decider_name")))
                }
                if (!isKitchen) rs.getString("note")?.let { put("note", it) }
                rs.getString("rejection_reason")?.let {
                    put("rejection", mapOf("reasonCode" to it, "reasonText" to rs.getString("rejection_text")))
                }
            }
        }.firstOrNull() ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found")
        val order = LinkedHashMap(orderRow)

        val items = jdbc.query(
            """
            SELECT oi.id, oi.product_id, oi.product_name, oi.product_name_i18n::text AS product_name_i18n,
                   oi.station_id, s.code AS station_code, oi.quantity, oi.note, oi.base_price_minor,
                   oi.options_delta_minor, oi.unit_price_minor, oi.line_total_minor, oi.status,
                   oi.queued_at, oi.started_at, oi.ready_at, oi.delivered_at, oi.cancelled_at,
                   oi.options_snapshot::text AS options_snapshot,
                   (p.available AND p.active AND c.active) AS product_currently_available
            FROM order_item oi JOIN station s ON s.id = oi.station_id
            JOIN product p ON p.id = oi.product_id
            JOIN subcategory sub ON sub.id = p.subcategory_id
            JOIN category c ON c.id = sub.category_id
            WHERE oi.order_id = :orderId
              $itemStationFilter
            ORDER BY oi.id
            """.trimIndent(),
            orderParams,
        ) { rs, _ ->
            val optionList = objectMapper.readValue(rs.getString("options_snapshot") ?: "[]", optionListType)
                .map { option -> if (isKitchen) option - "priceDeltaMinor" else option }
            linkedMapOf<String, Any>(
                "id" to rs.getString("id"),
                "productId" to rs.getString("product_id"),
                "productName" to rs.getString("product_name"),
                "productNameI18n" to objectMapper.readValue(rs.getString("product_name_i18n") ?: "{}", mapType),
                "quantity" to rs.getInt("quantity"),
                "station" to rs.getString("station_code"),
                "status" to rs.getString("status"),
                "options" to optionList,
                "productCurrentlyAvailable" to rs.getBoolean("product_currently_available"),
            ).apply {
                if (!isKitchen) {
                    put("basePriceMinor", rs.getLong("base_price_minor"))
                    put("optionsDeltaMinor", rs.getLong("options_delta_minor"))
                    put("unitPriceMinor", rs.getLong("unit_price_minor"))
                    put("lineTotalMinor", rs.getLong("line_total_minor"))
                }
                rs.getString("note")?.let { put("note", it) }
                rs.getTimestamp("queued_at")?.toInstant()?.toString()?.let { put("queuedAt", it) }
                rs.getTimestamp("started_at")?.toInstant()?.toString()?.let { put("startedAt", it) }
                rs.getTimestamp("ready_at")?.toInstant()?.toString()?.let { put("readyAt", it) }
                rs.getTimestamp("delivered_at")?.toInstant()?.toString()?.let { put("deliveredAt", it) }
                rs.getTimestamp("cancelled_at")?.toInstant()?.toString()?.let { put("cancelledAt", it) }
            }
        }
        order["items"] = items
        return order
    }

    override fun recordAudit(
        access: PreparationAccess,
        itemId: UUID,
        action: ItemAction,
        beforeStatus: String,
        afterStatus: String,
        reason: String?,
    ) {
        val auditAction = when (action) {
            ItemAction.CANCEL -> "ITEM_CANCELLED"
            ItemAction.UNDO -> "ITEM_STATUS_REVERTED"
            else -> "ITEM_STATUS_CHANGED"
        }
        auditService.record(
            restaurantId = access.restaurantId.toString(),
            actorType = "USER",
            actorId = access.userId.toString(),
            action = auditAction,
            targetType = "ORDER_ITEM",
            targetId = itemId.toString(),
            reason = reason,
            beforeSummary = """{"status":"$beforeStatus"}""",
            afterSummary = """{"status":"$afterStatus"}""",
        )
    }

    override fun undoWindowMinutes(restaurantId: UUID): Long =
        jdbc.query(
            "SELECT delivered_undo_window_min FROM restaurant_settings WHERE restaurant_id = :restaurantId",
            mapOf("restaurantId" to restaurantId),
        ) { rs, _ -> rs.getLong(1) }.firstOrNull()
            ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Restaurant settings not found")

    private companion object {
        val optionListType = object : TypeReference<List<Map<String, Any>>>() {}
        val mapType = object : TypeReference<Map<String, Any>>() {}
    }
}
