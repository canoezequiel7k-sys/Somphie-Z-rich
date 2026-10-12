package com.somphie.orders.api

import com.somphie.orders.domain.CreateOrderRequest
import com.somphie.orders.domain.ItemSnapshot
import com.somphie.orders.domain.Order
import com.somphie.orders.domain.OrderItem
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
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

@RestController
class OrdersController(
    private val jdbc: NamedParameterJdbcTemplate,
    private val idempotencyService: IdempotencyService,
    private val clock: Clock
) {

    @PostMapping("/sessions/current/orders")
    fun createOrder(
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestBody request: CreateOrderRequest
    ): ResponseEntity<Map<String, Any>> {
        if (request.items.isEmpty()) {
            throw DomainException(ErrorCode.ORDER_EMPTY, "Order must contain at least one item")
        }

        val scope = "order:create"
        val requestBodyJson = request.toString()

        return idempotencyService.executeIdempotent(scope, idempotencyKey, requestBodyJson) {
            val sessionAndParticipant = resolveSessionAndParticipant(authorization)
            val sessionId = sessionAndParticipant["sessionId"] as String
            val participantId = sessionAndParticipant["participantId"] as String
            val restaurantId = sessionAndParticipant["restaurantId"] as String

            val now = Instant.now(clock)

            val orderItems = request.items.map { itemDto ->
                val product = fetchProduct(itemDto.productId)
                val snapshot = ItemSnapshot(
                    productId = UUID.fromString(product["id"] as String),
                    productName = product["name"] as String,
                    stationId = UUID.fromString(product["station_id"] as String),
                    quantity = itemDto.quantity,
                    note = itemDto.note,
                    basePrice = Money.of(product["price_minor"] as Long)
                )
                OrderItem.pending(UUID.randomUUID(), snapshot)
            }

            val order = Order.create(
                id = UUID.randomUUID(),
                items = orderItems,
                now = now
            )

            val totalMinor = order.items.sumOf { it.lineTotal.minor }

            if (totalMinor != request.acknowledgedTotalMinor) {
                throw DomainException(ErrorCode.PAYMENT_AMOUNT_INVALID, "Price changed")
            }

            val insertOrderSql = """
                INSERT INTO customer_order (id, restaurant_id, session_id, participant_id, status, display_code, summary, total_minor, note, created_at)
                VALUES (CAST(:id AS UUID), CAST(:restaurantId AS UUID), CAST(:sessionId AS UUID), CAST(:participantId AS UUID), :status, :displayCode, :summary, :totalMinor, :note, :createdAt)
            """
            jdbc.update(insertOrderSql, mapOf(
                "id" to order.id.toString(),
                "restaurantId" to restaurantId,
                "sessionId" to sessionId,
                "participantId" to participantId,
                "status" to order.status.name,
                "displayCode" to "#001",
                "summary" to "Pedido de mesa",
                "totalMinor" to totalMinor,
                "note" to request.note,
                "createdAt" to java.sql.Timestamp.from(now)
            ))

            order.items.forEach { item ->
                val insertItemSql = """
                    INSERT INTO order_item (id, order_id, session_id, product_id, product_name, station_id, quantity, base_price_minor, unit_price_minor, line_total_minor, note, status)
                    VALUES (CAST(:id AS UUID), CAST(:orderId AS UUID), CAST(:sessionId AS UUID), CAST(:productId AS UUID), :productName, CAST(:stationId AS UUID), :quantity, :basePrice, :unitPrice, :lineTotal, :note, :status)
                """
                jdbc.update(insertItemSql, mapOf(
                    "id" to item.id.toString(),
                    "orderId" to order.id.toString(),
                    "sessionId" to sessionId,
                    "productId" to item.snapshot.productId.toString(),
                    "productName" to item.snapshot.productName,
                    "stationId" to item.snapshot.stationId.toString(),
                    "quantity" to item.snapshot.quantity,
                    "basePrice" to item.snapshot.basePrice.minor,
                    "unitPrice" to item.snapshot.unitPrice.minor,
                    "lineTotal" to item.snapshot.lineTotal.minor,
                    "note" to item.snapshot.note,
                    "status" to item.status.name
                ))
            }

            val responseBody = mapOf(
                "id" to order.id.toString(),
                "displayCode" to "#001",
                "status" to order.status.name,
                "totalMinor" to totalMinor
            )

            ResponseEntity.status(HttpStatus.CREATED).body(responseBody)
        }
    }

    @GetMapping("/sessions/current/orders")
    fun listSessionOrders(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?): ResponseEntity<List<Map<String, Any>>> {
        val sessionAndParticipant = resolveSessionAndParticipant(authorization)
        val sessionId = sessionAndParticipant["sessionId"] as String

        val sql = "SELECT id, status, display_code, total_minor, note, created_at FROM customer_order WHERE session_id = CAST(:sessionId AS UUID) ORDER BY created_at DESC"
        val orders = jdbc.query(sql, mapOf("sessionId" to sessionId)) { rs, _ ->
            mapOf(
                "id" to rs.getString("id"),
                "status" to rs.getString("status"),
                "displayCode" to rs.getString("display_code"),
                "totalMinor" to rs.getLong("total_minor"),
                "note" to rs.getString("note"),
                "createdAt" to rs.getTimestamp("created_at").toInstant().toString()
            )
        }

        return ResponseEntity.ok(orders)
    }

    @GetMapping("/orders/{orderId}")
    fun getOrder(@PathVariable orderId: String): ResponseEntity<Map<String, Any>> {
        val uuid = try {
            UUID.fromString(orderId)
        } catch (e: Exception) {
            throw DomainException(ErrorCode.ITEM_NOT_FOUND, "Order not found")
        }

        val sql = "SELECT id, status, display_code, total_minor, note, created_at FROM customer_order WHERE id = CAST(:id AS UUID)"
        val orders = jdbc.query(sql, mapOf("id" to uuid.toString())) { rs, _ ->
            mapOf(
                "id" to rs.getString("id"),
                "status" to rs.getString("status"),
                "displayCode" to rs.getString("display_code"),
                "totalMinor" to rs.getLong("total_minor"),
                "note" to rs.getString("note"),
                "createdAt" to rs.getTimestamp("created_at").toInstant().toString()
            )
        }

        val order = orders.firstOrNull() ?: throw DomainException(ErrorCode.ITEM_NOT_FOUND, "Order not found")
        return ResponseEntity.ok(order)
    }

    @PostMapping("/orders/{orderId}/cancel")
    fun cancelOrder(@PathVariable orderId: String): ResponseEntity<Map<String, Any>> {
        val uuid = try {
            UUID.fromString(orderId)
        } catch (e: Exception) {
            throw DomainException(ErrorCode.ITEM_NOT_FOUND, "Order not found")
        }

        val updated = jdbc.update(
            "UPDATE customer_order SET status = 'CANCELLED' WHERE id = CAST(:id AS UUID) AND status = 'PENDING_REVIEW'",
            mapOf("id" to uuid.toString())
        )

        if (updated == 0) {
            throw DomainException(ErrorCode.ORDER_NOT_CANCELLABLE, "Order cannot be cancelled")
        }

        return ResponseEntity.ok(mapOf("id" to uuid.toString(), "status" to "CANCELLED"))
    }

    private fun resolveSessionAndParticipant(authorization: String?): Map<String, String> {
        val activeSession = jdbc.query(
            "SELECT ts.id AS session_id, ts.restaurant_id, sp.id AS participant_id FROM table_session ts JOIN session_participant sp ON ts.id = sp.session_id WHERE ts.status = 'OPEN' LIMIT 1",
            emptyMap<String, Any>()
        ) { rs, _ ->
            mapOf(
                "sessionId" to rs.getString("session_id"),
                "participantId" to rs.getString("participant_id"),
                "restaurantId" to rs.getString("restaurant_id")
            )
        }.firstOrNull()

        return activeSession ?: throw DomainException(ErrorCode.SESSION_CLOSED, "No active session found")
    }

    private fun fetchProduct(productId: String): Map<String, Any> {
        val uuid = try {
            UUID.fromString(productId)
        } catch (e: Exception) {
            throw DomainException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found")
        }

        val product = jdbc.query(
            "SELECT id, name, price_minor, station_id FROM product WHERE id = CAST(:id AS UUID)",
            mapOf("id" to uuid.toString())
        ) { rs, _ ->
            mapOf(
                "id" to rs.getString("id"),
                "name" to rs.getString("name"),
                "price_minor" to rs.getLong("price_minor"),
                "station_id" to rs.getString("station_id")
            )
        }.firstOrNull()

        return product ?: throw DomainException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found")
    }
}
