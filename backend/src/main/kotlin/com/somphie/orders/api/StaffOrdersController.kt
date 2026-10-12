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
import java.time.Clock
import java.time.Instant
import java.util.UUID

@RestController
class StaffOrdersController(
    private val jdbc: NamedParameterJdbcTemplate,
    private val idempotencyService: IdempotencyService,
    private val clock: Clock,
    private val identityService: IdentityService,
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

        val sql = """
            SELECT o.id, o.session_id, o.status, o.display_code, o.total_minor, o.note, o.created_at
            FROM customer_order o
            WHERE o.id = CAST(:id AS UUID)
              AND o.restaurant_id = CAST(:restaurantId AS UUID)
              AND (:restrictStation = FALSE OR EXISTS (
                  SELECT 1 FROM order_item oi
                  JOIN user_station us ON us.station_id = oi.station_id
                  JOIN station s ON s.id = us.station_id
                  WHERE oi.order_id = o.id AND oi.status IN ('QUEUED', 'IN_PREPARATION', 'READY', 'DELIVERED')
                    AND us.user_id = CAST(:userId AS UUID)
                    AND s.restaurant_id = CAST(:restaurantId AS UUID)
              ))
        """.trimIndent()
        val orders = jdbc.query(
            sql,
            mapOf(
                "id" to uuid.toString(),
                "restaurantId" to profile.account.restaurantId.toString(),
                "restrictStation" to profile.roles.contains("KITCHEN"),
                "userId" to profile.account.id.toString(),
            ),
        ) { rs, _ ->
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

        val order = orders.firstOrNull() ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found")
        return ResponseEntity.ok(order)
    }

    @PostMapping("/staff/orders/{orderId}/accept")
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

            updateOrderInDb(acceptedOrder, restaurantId)

            ResponseEntity.ok(mapOf("id" to orderId, "status" to acceptedOrder.status.name))
        }
    }

    @PostMapping("/staff/orders/{orderId}/reject")
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

            updateOrderInDb(rejectedOrder, restaurantId)

            ResponseEntity.ok(mapOf("id" to orderId, "status" to rejectedOrder.status.name))
        }
    }

    private fun fetchOrderAggregate(orderId: UUID, restaurantId: String): Order {
        val orderSql = """
            SELECT id, status, created_at, accepted_at, decided_at, rejection_reason, rejection_text
            FROM customer_order WHERE id = CAST(:id AS UUID)
              AND restaurant_id = CAST(:restaurantId AS UUID)
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
            
            // Fetch items
            val itemSql = "SELECT id, product_id, product_name, station_id, quantity, base_price_minor, unit_price_minor, line_total_minor, note, status FROM order_item WHERE order_id = CAST(:orderId AS UUID)"
            val items = jdbc.query(itemSql, mapOf("orderId" to rs.getString("id"))) { itemRs, _ ->
                val snapshot = ItemSnapshot(
                    productId = UUID.fromString(itemRs.getString("product_id")),
                    productName = itemRs.getString("product_name"),
                    stationId = UUID.fromString(itemRs.getString("station_id")),
                    quantity = itemRs.getInt("quantity"),
                    note = itemRs.getString("note"),
                    basePrice = Money.of(itemRs.getLong("base_price_minor"))
                )
                OrderItem.pending(UUID.fromString(itemRs.getString("id")), snapshot)
            }

            // Reconstruct order domain aggregate via private/protected constructor or create + transition
            // Since Order constructor is private, we can use create and apply accepted/rejected state if needed, or reconstruct.
            // Wait, let's check how Order can be reconstructed or if we can instantiate it or call accept/reject on a created order.
            // Actually, if we store the items with their current statuses, or if we use Order.create + apply.
            // Let's create a helper to build Order from DB items.
            Triple(status, createdAt, items)
        }

        val record = orderRows.firstOrNull() ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found")
        val (status, createdAt, items) = record

        val order = Order.create(orderId, items, createdAt)
        return when (status) {
            OrderStatus.ACCEPTED, OrderStatus.IN_PREPARATION, OrderStatus.READY, OrderStatus.DELIVERED -> order.accept(createdAt)
            OrderStatus.REJECTED -> order.reject(createdAt, Rejection(RejectionReason.OTHER))
            OrderStatus.CANCELLED -> order.cancel(createdAt)
            OrderStatus.EXPIRED -> order.expire(createdAt, java.time.Duration.ZERO)
            else -> order
        }
    }

    private fun updateOrderInDb(order: Order, restaurantId: String) {
        val updateOrderSql = """
            UPDATE customer_order
            SET status = :status, accepted_at = :acceptedAt, decided_at = :decidedAt,
                rejection_reason = :rejectionReason, rejection_text = :rejectionText
            WHERE id = CAST(:id AS UUID) AND restaurant_id = CAST(:restaurantId AS UUID)
        """
        jdbc.update(updateOrderSql, mapOf(
            "status" to order.status.name,
            "acceptedAt" to order.acceptedAt?.let { java.sql.Timestamp.from(it) },
            "decidedAt" to order.decidedAt?.let { java.sql.Timestamp.from(it) },
            "rejectionReason" to order.rejection?.reason?.name,
            "rejectionText" to order.rejection?.text,
            "id" to order.id.toString(),
            "restaurantId" to restaurantId,
        ))

        order.items.forEach { item ->
            jdbc.update(
                """
                UPDATE order_item SET status = :status
                WHERE id = CAST(:id AS UUID) AND EXISTS (
                    SELECT 1 FROM customer_order o
                    WHERE o.id = order_item.order_id AND o.restaurant_id = CAST(:restaurantId AS UUID)
                )
                """.trimIndent(),
                mapOf("status" to item.status.name, "id" to item.id.toString(), "restaurantId" to restaurantId)
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
}
