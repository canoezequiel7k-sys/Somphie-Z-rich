package com.somphie.orders.api

import com.somphie.identity.application.IdentityService
import com.somphie.identity.domain.StaffProfile
import com.somphie.orders.domain.AcceptOrderRequest
import com.somphie.orders.domain.ItemSnapshot
import com.somphie.orders.domain.ItemStatus
import com.somphie.orders.domain.Order
import com.somphie.orders.domain.OrderItem
import com.somphie.orders.domain.OrderStatus
import com.somphie.orders.domain.Rejection
import com.somphie.orders.domain.RejectionReason
import com.somphie.orders.domain.RejectOrderRequest
import com.somphie.preparation.application.GetStaffPreparationOrder
import com.somphie.preparation.application.PreparationAccess
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.domain.Money
import com.somphie.shared.idempotency.IdempotencyService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

@RestController
class StaffOrdersController(
    private val jdbc: NamedParameterJdbcTemplate,
    private val idempotencyService: IdempotencyService,
    private val clock: Clock,
    private val identityService: IdentityService,
    private val objectMapper: ObjectMapper,
    private val getStaffPreparationOrder: GetStaffPreparationOrder,
) {

    @GetMapping("/staff/orders")
    fun listStaffOrders(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) sessionId: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(required = false) cursor: String?,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Map<String, Any?>> {
        val profile = authorize(jwt, "order.read")
        if (limit !in 1..100) throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid limit")
        val statuses = status?.split(',')?.map { value ->
            try {
                OrderStatus.valueOf(value.trim()).name
            } catch (_: IllegalArgumentException) {
                throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid order status")
            }
        }?.distinct()
        val sessionUuid = sessionId?.let {
            try {
                UUID.fromString(it)
            } catch (_: IllegalArgumentException) {
                throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid sessionId")
            }
        }
        val after = cursor?.let(OrderCursorCodec::decode)
        val isKitchen = "KITCHEN" in profile.roles
        val stationIds = profile.stations.map { it.id }
        if (isKitchen && stationIds.isEmpty()) {
            return ResponseEntity.ok(mapOf("items" to emptyList<Any>(), "nextCursor" to null))
        }

        val params = mutableMapOf<String, Any>(
            "restaurantId" to profile.account.restaurantId,
            "limit" to limit + 1,
        )
        val filters = mutableListOf(
            "o.restaurant_id = :restaurantId",
            """
            (:restrictStation = FALSE OR EXISTS (
                SELECT 1 FROM order_item scoped
                JOIN user_station us ON us.station_id = scoped.station_id
                JOIN station s ON s.id = us.station_id
                WHERE scoped.order_id = o.id
                  AND scoped.status IN ('QUEUED', 'IN_PREPARATION', 'READY', 'DELIVERED')
                  AND us.user_id = :userId
                  AND s.restaurant_id = :restaurantId
            ))
            """.trimIndent(),
        )
        params["restrictStation"] = isKitchen
        params["userId"] = profile.account.id
        if (statuses != null) {
            if (statuses.isEmpty()) throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid order status")
            filters += "o.status IN (:statuses)"
            params["statuses"] = statuses
        }
        if (sessionUuid != null) {
            filters += "o.session_id = :sessionId"
            params["sessionId"] = sessionUuid
        }
        if (after != null) {
            params["cursorAt"] = java.sql.Timestamp.from(after.createdAt)
            params["cursorId"] = after.id
            filters += "(o.created_at, o.id) > (:cursorAt, :cursorId)"
        }

        // La bandeja se recorre por antigüedad; el UUID desempata y hace estable el cursor keyset.
        val rows = jdbc.query(
            """
            SELECT o.id, o.session_id, o.status, o.display_code, o.version, o.total_minor, o.note,
                   o.created_at, o.decided_at, o.decided_by, o.rejection_reason, o.rejection_text,
                   p.id AS participant_id, p.display_name AS participant_name,
                   dt.label AS table_label, u.display_name AS decider_name
            FROM customer_order o
            JOIN table_session ts ON ts.id = o.session_id
            JOIN dining_table dt ON dt.id = ts.table_id
            JOIN session_participant p ON p.id = o.participant_id
            LEFT JOIN app_user u ON u.id = o.decided_by
            WHERE ${filters.joinToString(" AND ")}
            ORDER BY o.created_at ASC, o.id ASC
            LIMIT :limit
            """.trimIndent(),
            params,
        ) { rs, _ ->
            val id = rs.getObject("id", UUID::class.java)
            val createdAt = rs.getTimestamp("created_at").toInstant()
            val order = linkedMapOf<String, Any>(
                "id" to id.toString(),
                "sessionId" to rs.getString("session_id"),
                "tableLabel" to rs.getString("table_label"),
                "status" to rs.getString("status"),
                "displayCode" to rs.getString("display_code"),
                "version" to rs.getLong("version"),
                "createdAt" to createdAt.toString(),
                "items" to emptyList<Any>(),
                "totalMinor" to rs.getLong("total_minor"),
            ).apply {
                if (!isKitchen) {
                    put("createdBy", mapOf(
                        "participantId" to rs.getString("participant_id"),
                        "displayName" to rs.getString("participant_name"),
                    ))
                }
                rs.getTimestamp("decided_at")?.toInstant()?.toString()?.let { put("decidedAt", it) }
                rs.getObject("decided_by", UUID::class.java)?.let {
                    put("decidedBy", mapOf("userId" to it.toString(), "displayName" to rs.getString("decider_name")))
                }
                rs.getString("note")?.let { put("note", it) }
                rs.getString("rejection_reason")?.let {
                    put("rejection", linkedMapOf("reasonCode" to it).apply {
                        rs.getString("rejection_text")?.let { text -> put("reasonText", text) }
                    })
                }
            }
            StaffOrderRow(id, createdAt, order)
        }

        val hasMore = rows.size > limit
        val page = rows.take(limit)
        if (page.isNotEmpty()) {
            val itemFilters = if (isKitchen) "AND oi.station_id IN (:stationIds)" else ""
            if (isKitchen) params["stationIds"] = stationIds
            val items = jdbc.query(
                """
                SELECT oi.order_id, oi.id, oi.product_id, oi.product_name,
                       oi.product_name_i18n::text AS product_name_i18n,
                       s.code AS station_code, oi.quantity, oi.note, oi.base_price_minor,
                       oi.options_delta_minor, oi.unit_price_minor, oi.line_total_minor, oi.status,
                       oi.queued_at, oi.started_at, oi.ready_at, oi.delivered_at, oi.cancelled_at,
                       oi.options_snapshot::text AS options_snapshot,
                       (p.available AND p.active AND c.active) AS product_currently_available
                FROM order_item oi
                JOIN station s ON s.id = oi.station_id
                JOIN product p ON p.id = oi.product_id
                JOIN subcategory sub ON sub.id = p.subcategory_id
                JOIN category c ON c.id = sub.category_id
                WHERE oi.order_id IN (:orderIds)
                  AND s.restaurant_id = :restaurantId
                  $itemFilters
                ORDER BY oi.order_id, oi.id
                """.trimIndent(),
                params + mapOf("orderIds" to page.map { it.id }),
            ) { rs, _ ->
                val item = linkedMapOf<String, Any>(
                    "id" to rs.getString("id"),
                    "productId" to rs.getString("product_id"),
                    "productName" to rs.getString("product_name"),
                    "productNameI18n" to objectMapper.readValue(rs.getString("product_name_i18n") ?: "{}", stringMapType),
                    "basePriceMinor" to rs.getLong("base_price_minor"),
                    "optionsDeltaMinor" to rs.getLong("options_delta_minor"),
                    "unitPriceMinor" to rs.getLong("unit_price_minor"),
                    "quantity" to rs.getInt("quantity"),
                    "lineTotalMinor" to rs.getLong("line_total_minor"),
                    "station" to rs.getString("station_code"),
                    "status" to rs.getString("status"),
                    "options" to objectMapper.readValue(rs.getString("options_snapshot") ?: "[]", optionListType),
                    "productCurrentlyAvailable" to rs.getBoolean("product_currently_available"),
                ).apply {
                    rs.getString("note")?.let { put("note", it) }
                    listOf(
                        "queued_at" to "queuedAt",
                        "started_at" to "startedAt",
                        "ready_at" to "readyAt",
                        "delivered_at" to "deliveredAt",
                        "cancelled_at" to "cancelledAt",
                    ).forEach { (column, field) ->
                        rs.getTimestamp(column)?.toInstant()?.toString()?.let { put(field, it) }
                    }
                }
                rs.getObject("order_id", UUID::class.java) to item
            }
            val itemsByOrder = items.groupBy({ it.first }, { it.second })
            page.forEach { it.response["items"] = itemsByOrder[it.id].orEmpty() }
        }
        val nextCursor = if (hasMore) {
            page.lastOrNull()?.let { OrderCursorCodec.encode(it.createdAt, it.id) }
        } else {
            null
        }
        return ResponseEntity.ok(mapOf("items" to page.map { it.response }, "nextCursor" to nextCursor))
    }

    @GetMapping("/staff/orders/{orderId}")
    fun getStaffOrder(
        @PathVariable orderId: String,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Map<String, Any>> {
        val profile = authorize(jwt, "order.read")
        val uuid = parseOrderId(orderId)
        val access = PreparationAccess(
            profile.account.restaurantId,
            profile.account.id,
            profile.account.displayName,
            profile.roles.toSet(),
            profile.permissions.toSet(),
            profile.stations.map { it.id }.toSet(),
        )
        return ResponseEntity.ok(getStaffPreparationOrder.execute(access, uuid))
    }

    @PostMapping("/staff/orders/{orderId}/accept")
    @Transactional
    fun acceptOrder(
        @PathVariable orderId: String,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody(required = false) request: AcceptOrderRequest?,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Map<String, Any>> {
        val profile = authorize(jwt, "order.accept")
        val restaurantId = profile.account.restaurantId.toString()
        val scope = "order:accept"
        val requestBodyJson = request?.toString() ?: "{}"

        return idempotencyService.executeIdempotent(scope, idempotencyKey, requestBodyJson) {
            val uuid = parseOrderId(orderId)
            val orderRecord = fetchOrderAggregate(uuid, restaurantId)
            val now = Instant.now(clock)

            val excludedIds = request?.excludedItems?.map { UUID.fromString(it.itemId) }?.toSet() ?: emptySet()
            val acceptedOrder = orderRecord.accept(now, excludedIds)

            updateOrderInDb(acceptedOrder, restaurantId, profile.account.id)

            ResponseEntity.ok(
                getStaffPreparationOrder.execute(
                    PreparationAccess(
                        profile.account.restaurantId, profile.account.id, profile.account.displayName,
                        profile.roles.toSet(), profile.permissions.toSet(), profile.stations.map { it.id }.toSet(),
                    ),
                    uuid,
                ),
            )
        }
    }

    @PostMapping("/staff/orders/{orderId}/reject")
    @Transactional
    fun rejectOrder(
        @PathVariable orderId: String,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody request: RejectOrderRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Map<String, Any>> {
        val profile = authorize(jwt, "order.reject")
        val restaurantId = profile.account.restaurantId.toString()
        val scope = "order:reject"
        val requestBodyJson = request.toString()

        return idempotencyService.executeIdempotent(scope, idempotencyKey, requestBodyJson) {
            val uuid = parseOrderId(orderId)
            val orderRecord = fetchOrderAggregate(uuid, restaurantId)
            val now = Instant.now(clock)

            val reason = try {
                RejectionReason.valueOf(request.reasonCode)
            } catch (e: Exception) {
                RejectionReason.OTHER
            }

            val rejection = Rejection(reason, request.reasonText)
            val rejectedOrder = orderRecord.reject(now, rejection)

            updateOrderInDb(rejectedOrder, restaurantId, profile.account.id)

            ResponseEntity.ok(
                getStaffPreparationOrder.execute(
                    PreparationAccess(
                        profile.account.restaurantId, profile.account.id, profile.account.displayName,
                        profile.roles.toSet(), profile.permissions.toSet(), profile.stations.map { it.id }.toSet(),
                    ),
                    uuid,
                ),
            )
        }
    }

    private fun fetchOrderAggregate(orderId: UUID, restaurantId: String): Order {
        val sessionId = jdbc.query(
            """
            SELECT session_id FROM customer_order
            WHERE id = CAST(:id AS UUID) AND restaurant_id = CAST(:restaurantId AS UUID)
            """.trimIndent(),
            mapOf("id" to orderId.toString(), "restaurantId" to restaurantId),
        ) { rs, _ -> rs.getObject("session_id", UUID::class.java) }.firstOrNull()
            ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found")
        // Alinear con preparación y expiración evita que una transacción tome pedido y sesión en orden inverso.
        val sessionLocked = jdbc.query(
            """
            SELECT id FROM table_session
            WHERE id = :sessionId AND restaurant_id = CAST(:restaurantId AS UUID)
            FOR UPDATE
            """.trimIndent(),
            mapOf("sessionId" to sessionId, "restaurantId" to restaurantId),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }.isNotEmpty()
        if (!sessionLocked) throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found")

        val orderSql = """
            SELECT id, status, created_at, accepted_at, decided_at, rejection_reason, rejection_text, version
            FROM customer_order WHERE id = CAST(:id AS UUID)
              AND restaurant_id = CAST(:restaurantId AS UUID)
            FOR UPDATE
        """.trimIndent()
        val orderRows = jdbc.query(
            orderSql,
            mapOf("id" to orderId.toString(), "restaurantId" to restaurantId),
        ) { rs, _ ->
            val status = OrderStatus.valueOf(rs.getString("status"))
            val createdAt = rs.getTimestamp("created_at").toInstant()
            val acceptedAt = rs.getTimestamp("accepted_at")?.toInstant()
            val decidedAt = rs.getTimestamp("decided_at")?.toInstant()
            val reasonStr = rs.getString("rejection_reason")
            val rejection = if (reasonStr != null) Rejection(RejectionReason.valueOf(reasonStr), rs.getString("rejection_text")) else null
            val version = rs.getLong("version")
            val itemSql = """
                SELECT id, product_id, product_name, product_name_i18n::text AS product_name_i18n,
                       station_id, quantity, base_price_minor, note, status, queued_at, started_at,
                       ready_at, delivered_at, cancelled_at, options_snapshot::text AS options_snapshot
                FROM order_item WHERE order_id = CAST(:orderId AS UUID) ORDER BY id FOR UPDATE
            """.trimIndent()
            val items = jdbc.query(itemSql, mapOf("orderId" to rs.getString("id"))) { itemRs, _ ->
                val options = objectMapper.readValue(itemRs.getString("options_snapshot") ?: "[]", optionListType).map {
                    com.somphie.orders.domain.OptionSnapshot(
                        UUID.fromString(it.getValue("groupId").toString()),
                        UUID.fromString(it.getValue("optionId").toString()),
                        it.getValue("groupName").toString(),
                        it.getValue("optionName").toString(),
                        Money.of((it.getValue("priceDeltaMinor") as Number).toLong()),
                    )
                }
                val snapshot = ItemSnapshot(
                    productId = UUID.fromString(itemRs.getString("product_id")),
                    productName = itemRs.getString("product_name"),
                    productNameI18n = objectMapper.readValue(itemRs.getString("product_name_i18n") ?: "{}", stringMapType),
                    stationId = UUID.fromString(itemRs.getString("station_id")),
                    quantity = itemRs.getInt("quantity"),
                    note = itemRs.getString("note"),
                    basePrice = Money.of(itemRs.getLong("base_price_minor")),
                    options = options,
                )
                OrderItem.restore(
                    UUID.fromString(itemRs.getString("id")),
                    snapshot,
                    ItemStatus.valueOf(itemRs.getString("status")),
                    itemRs.getTimestamp("queued_at")?.toInstant(),
                    itemRs.getTimestamp("started_at")?.toInstant(),
                    itemRs.getTimestamp("ready_at")?.toInstant(),
                    itemRs.getTimestamp("delivered_at")?.toInstant(),
                    itemRs.getTimestamp("cancelled_at")?.toInstant(),
                )
            }
            Order.restore(
                id = UUID.fromString(rs.getString("id")),
                status = status,
                items = items,
                createdAt = createdAt,
                acceptedAt = acceptedAt,
                decidedAt = decidedAt,
                rejection = rejection,
                version = version,
            )
        }

        return orderRows.firstOrNull() ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found")
    }

    private fun updateOrderInDb(order: Order, restaurantId: String, actorId: UUID) {
        val activityUpdated = jdbc.update(
            """
            UPDATE table_session ts
            SET last_activity_at = :now
            WHERE ts.id = (
                SELECT session_id FROM customer_order
                WHERE id = :orderId AND restaurant_id = CAST(:restaurantId AS UUID)
            ) AND ts.status = 'OPEN'
            """.trimIndent(),
            mapOf(
                "now" to order.decidedAt?.let(java.sql.Timestamp::from),
                "orderId" to order.id,
                "restaurantId" to restaurantId,
            ),
        )
        if (activityUpdated != 1) throw DomainException(ErrorCode.SESSION_CLOSED, "Session is closed")

        val updateOrderSql = """
            UPDATE customer_order
            SET status = :status, accepted_at = :acceptedAt, decided_at = :decidedAt,
                rejection_reason = :rejectionReason, rejection_text = :rejectionText,
                decided_by = :actorId, version = :version
            WHERE id = CAST(:id AS UUID) AND restaurant_id = CAST(:restaurantId AS UUID)
              AND version = :expectedVersion
        """
        val changed = jdbc.update(updateOrderSql, mapOf(
            "status" to order.status.name,
            "acceptedAt" to order.acceptedAt?.let { java.sql.Timestamp.from(it) },
            "decidedAt" to order.decidedAt?.let { java.sql.Timestamp.from(it) },
            "rejectionReason" to order.rejection?.reason?.name,
            "rejectionText" to order.rejection?.text,
            "actorId" to actorId,
            "version" to order.version,
            "expectedVersion" to order.version - 1,
            "id" to order.id.toString(),
            "restaurantId" to restaurantId,
        ))
        if (changed != 1) throw DomainException(ErrorCode.ORDER_ALREADY_DECIDED, "Order was concurrently modified")

        order.items.forEach { item ->
            jdbc.update(
                """
                UPDATE order_item SET status = :status, queued_at = :queuedAt, started_at = :startedAt,
                    ready_at = :readyAt, delivered_at = :deliveredAt, cancelled_at = :cancelledAt
                WHERE id = CAST(:id AS UUID) AND EXISTS (
                    SELECT 1 FROM customer_order o
                    WHERE o.id = order_item.order_id AND o.restaurant_id = CAST(:restaurantId AS UUID)
                )
                """.trimIndent(),
                mapOf(
                    "status" to item.status.name,
                    "queuedAt" to item.queuedAt?.let(java.sql.Timestamp::from),
                    "startedAt" to item.startedAt?.let(java.sql.Timestamp::from),
                    "readyAt" to item.readyAt?.let(java.sql.Timestamp::from),
                    "deliveredAt" to item.deliveredAt?.let(java.sql.Timestamp::from),
                    "cancelledAt" to item.cancelledAt?.let(java.sql.Timestamp::from),
                    "id" to item.id.toString(),
                    "restaurantId" to restaurantId,
                )
            )
        }
    }

    private fun authorize(jwt: Jwt, permission: String): StaffProfile {
        val profile = identityService.me(jwt.subject, jwt.getClaim("rid"), jwt.getClaim("tv"))
        if (permission !in profile.permissions) {
            throw DomainException(ErrorCode.FORBIDDEN, "Staff permission is required")
        }
        return profile
    }

    private fun parseOrderId(orderId: String): UUID = try {
        UUID.fromString(orderId)
    } catch (_: IllegalArgumentException) {
        throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found")
    }

    private companion object {
        data class StaffOrderRow(
            val id: UUID,
            val createdAt: Instant,
            val response: LinkedHashMap<String, Any>,
        )

        val optionListType = object : TypeReference<List<Map<String, Any>>>() {}
        val stringMapType = object : TypeReference<Map<String, String>>() {}
    }
}
