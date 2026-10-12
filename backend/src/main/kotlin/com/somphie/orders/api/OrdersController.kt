package com.somphie.orders.api

import com.somphie.audit.AuditService
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
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

@RestController
class OrdersController(
    private val jdbc: NamedParameterJdbcTemplate,
    private val idempotencyService: IdempotencyService,
    private val auditService: AuditService,
    private val clock: Clock
) {

    @PostMapping("/sessions/current/orders")
    fun createOrder(
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestBody request: CreateOrderRequest
    ): ResponseEntity<Map<String, Any>> {
        val sessionAndParticipant = resolveSessionAndParticipant(authorization)
        requireOpenSession(sessionAndParticipant)
        if (request.items.isEmpty()) {
            throw DomainException(ErrorCode.ORDER_EMPTY, "Order must contain at least one item")
        }

        val scope = "order:create"
        val requestBodyJson = request.toString()
        return idempotencyService.executeIdempotent(scope, idempotencyKey, requestBodyJson) {
            val sessionId = sessionAndParticipant["sessionId"] as String
            val participantId = sessionAndParticipant["participantId"] as String
            val restaurantId = sessionAndParticipant["restaurantId"] as String

            val now = Instant.now(clock)

            val orderItems = request.items.map { itemDto ->
                val product = fetchProduct(itemDto.productId, restaurantId)
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

            // Record audit event in the same transaction
            auditService.record(
                restaurantId = restaurantId,
                actorType = "CUSTOMER",
                actorId = participantId,
                action = "ORDER_CREATED",
                targetType = "ORDER",
                targetId = order.id.toString(),
                afterSummary = "{\"status\":\"${order.status.name}\",\"totalMinor\":$totalMinor}"
            )

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
    fun getOrder(
        @PathVariable orderId: String,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
    ): ResponseEntity<Map<String, Any>> {
        val sessionAndParticipant = resolveSessionAndParticipant(authorization)
        val uuid = try {
            UUID.fromString(orderId)
        } catch (e: Exception) {
            throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found")
        }

        val sql = "SELECT id, status, display_code, total_minor, note, created_at FROM customer_order WHERE id = CAST(:id AS UUID) AND session_id = CAST(:sessionId AS UUID)"
        val orders = jdbc.query(sql, mapOf("id" to uuid.toString(), "sessionId" to sessionAndParticipant["sessionId"])) { rs, _ ->
            mapOf(
                "id" to rs.getString("id"),
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

    @PostMapping("/orders/{orderId}/cancel")
    fun cancelOrder(
        @PathVariable orderId: String,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
    ): ResponseEntity<Map<String, Any>> {
        val sessionAndParticipant = resolveSessionAndParticipant(authorization)
        requireOpenSession(sessionAndParticipant)
        val uuid = try {
            UUID.fromString(orderId)
        } catch (e: Exception) {
            throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found")
        }

        val sessionId = sessionAndParticipant.getValue("sessionId")
        val participantId = sessionAndParticipant.getValue("participantId")
        val order = jdbc.query(
            """
            SELECT participant_id FROM customer_order
            WHERE id = CAST(:id AS UUID) AND session_id = CAST(:sessionId AS UUID)
            """.trimIndent(),
            mapOf("id" to uuid.toString(), "sessionId" to sessionId),
        ) { rs, _ -> rs.getString("participant_id") }.firstOrNull()
            ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found")
        if (order != participantId && sessionAndParticipant["role"] != "HOST") {
            throw DomainException(ErrorCode.FORBIDDEN, "Only the author or session host can cancel this order")
        }

        val updated = jdbc.update(
            "UPDATE customer_order SET status = 'CANCELLED' WHERE id = CAST(:id AS UUID) AND session_id = CAST(:sessionId AS UUID) AND status = 'PENDING_REVIEW'",
            mapOf("id" to uuid.toString(), "sessionId" to sessionId)
        )

        if (updated == 0) {
            throw DomainException(ErrorCode.ORDER_NOT_CANCELLABLE, "Order cannot be cancelled")
        }

        return ResponseEntity.ok(mapOf("id" to uuid.toString(), "status"  to "CANCELLED"))
    }

    private fun resolveSessionAndParticipant(authorization: String?): Map<String, String> {
        val token = authorization
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.takeIf { it.isNotBlank() }
            ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "Guest token is required")
        val tokenHash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
        )
        val participant = jdbc.query(
            """
            SELECT ts.id AS session_id, ts.restaurant_id, ts.status AS session_status,
                   ts.closed_at, sp.id AS participant_id, sp.role, sp.revoked_at
            FROM table_session ts
            JOIN session_participant sp ON ts.id = sp.session_id
            WHERE sp.token_hash = :tokenHash
            """.trimIndent(),
            mapOf("tokenHash" to tokenHash),
        ) { rs, _ ->
            mapOf(
                "sessionId" to rs.getString("session_id"),
                "participantId" to rs.getString("participant_id"),
                "restaurantId" to rs.getString("restaurant_id"),
                "sessionStatus" to rs.getString("session_status"),
                "closedAt" to rs.getTimestamp("closed_at")?.toInstant()?.toString().orEmpty(),
                "role" to rs.getString("role"),
                "revoked" to rs.getTimestamp("revoked_at")?.toInstant()?.toString().orEmpty(),
            )
        }.firstOrNull() ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "Invalid guest token")

        if (participant["revoked"].orEmpty().isNotEmpty()) {
            throw DomainException(ErrorCode.SESSION_ACCESS_REVOKED, "Guest access was revoked")
        }
        if (participant["sessionStatus"] == "CLOSED") {
            val closedAt = participant["closedAt"]?.let(Instant::parse)
            if (closedAt == null || closedAt.isBefore(Instant.now(clock).minusSeconds(CLOSED_SESSION_READ_SECONDS))) {
                throw DomainException(ErrorCode.SESSION_CLOSED, "Session is no longer available")
            }
        }

        return participant
    }

    private fun requireOpenSession(session: Map<String, String>) {
        if (session["sessionStatus"] != "OPEN") {
            throw DomainException(ErrorCode.SESSION_CLOSED, "Session is closed")
        }
    }

    private fun fetchProduct(productId: String, restaurantId: String): Map<String, Any> {
        val uuid = try {
            UUID.fromString(productId)
        } catch (e: Exception) {
            throw DomainException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found")
        }

        val product = jdbc.query(
            """
            SELECT p.id, p.name, p.price_minor, p.station_id
            FROM product p
            JOIN subcategory sub ON sub.id = p.subcategory_id
            JOIN category c ON c.id = sub.category_id
            WHERE p.id = CAST(:id AS UUID) AND c.restaurant_id = CAST(:restaurantId AS UUID)
            """.trimIndent(),
            mapOf("id" to uuid.toString(), "restaurantId" to restaurantId)
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

    private companion object {
        const val CLOSED_SESSION_READ_SECONDS = 24 * 60 * 60L
    }
}
