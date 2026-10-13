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
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<List<Map<String, Any>>> {
        val profile = authorize(jwt, "order.read")
        var sql = """
            SELECT o.id, o.session_id, o.status, o.display_code, o.total_minor, o.note, o.created_at
            FROM customer_order o
            WHERE o.restaurant_id = CAST(:restaurantId AS UUID)
              AND (:restrictStation = FALSE OR EXISTS (
                  SELECT 1 FROM order_item oi
                  JOIN user_station us ON us.station_id = oi.station_id
                  JOIN station s ON s.id = us.station_id
                  WHERE oi.order_id = o.id AND oi.status IN ('QUEUED', 'IN_PREPARATION', 'READY', 'DELIVERED')
                    AND us.user_id = CAST(:userId AS UUID)
                    AND s.restaurant_id = CAST(:restaurantId AS UUID)
              ))
        """.trimIndent()
        val params = mutableMapOf<String, Any>(
            "restaurantId" to profile.account.restaurantId.toString(),
            "restrictStation" to profile.roles.contains("KITCHEN"),
            "userId" to profile.account.id.toString(),
        )

        if (!status.isNullOrBlank()) {
            sql += " AND o.status = :status"
            params["status"] = status
        }
        if (!sessionId.isNullOrBlank()) {
            sql += " AND o.session_id = CAST(:sessionId AS UUID)"
            params["sessionId"] = sessionId
        }
        sql += " ORDER BY o.created_at DESC"

        val orders = jdbc.query(sql, params) { rs, _ ->
            mapOf(
                "id" to rs.getString("id"),
                "sessionId" to rs.getString("session_id"),
                "status" to rs.getString("status"),
                "displayCode" to rs.getString("display_code"),
                "totalMinor" to rs.getLong("total_minor"),
                "note" to rs.getString("note"),
                "createdAt" to rs.getTimestamp("created_at").toInstant().toString()
            )
        }

        return ResponseEntity.ok(orders)
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
        val optionListType = object : TypeReference<List<Map<String, Any>>>() {}
        val stringMapType = object : TypeReference<Map<String, String>>() {}
    }
}
