package com.somphie.orders.api

import com.somphie.audit.AuditService
import com.somphie.orders.domain.CreateOrderRequest
import com.somphie.orders.domain.ItemSnapshot
import com.somphie.orders.domain.Order
import com.somphie.orders.domain.OrderItem
import com.somphie.orders.domain.OptionSnapshot
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
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
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
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) {
    private data class ProductRecord(
        val id: String,
        val name: String,
        val nameI18n: Map<String, String>,
        val priceMinor: Long,
        val stationId: String,
        val stationCode: String,
        val available: Boolean,
    )

    @PostMapping("/sessions/current/orders")
    @Transactional
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

            val stationCodes = mutableMapOf<UUID, String>()
            val orderItems = request.items.map { itemDto ->
                val product = fetchProduct(itemDto.productId, restaurantId)
                stationCodes[UUID.fromString(product.stationId)] = product.stationCode
                if (!product.available) {
                    throw DomainException(ErrorCode.PRODUCT_UNAVAILABLE, "Product is unavailable")
                }
                val optionSnapshots = validateAndSnapshotOptions(
                    productId = product.id,
                    restaurantId = restaurantId,
                    requestedOptionIds = itemDto.optionIds.orEmpty(),
                )
                val snapshot = ItemSnapshot(
                    productId = UUID.fromString(product.id),
                    productName = product.name,
                    productNameI18n = product.nameI18n,
                    stationId = UUID.fromString(product.stationId),
                    quantity = itemDto.quantity,
                    note = itemDto.note,
                    basePrice = Money.of(product.priceMinor),
                    options = optionSnapshots,
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
                throw DomainException(ErrorCode.PRICE_CHANGED, "Price changed", mapOf("currentTotalMinor" to totalMinor))
            }

            val activityUpdated = jdbc.update(
                "UPDATE table_session SET last_activity_at = :now WHERE id = CAST(:sessionId AS UUID) AND status = 'OPEN'",
                mapOf("sessionId" to sessionId, "now" to java.sql.Timestamp.from(now)),
            )
            if (activityUpdated == 0) {
                throw DomainException(ErrorCode.SESSION_CLOSED, "Session is closed")
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
                    INSERT INTO order_item (id, order_id, session_id, product_id, product_name, product_name_i18n, station_id, quantity, base_price_minor, options_delta_minor, unit_price_minor, line_total_minor, note, status, options_snapshot)
                    VALUES (CAST(:id AS UUID), CAST(:orderId AS UUID), CAST(:sessionId AS UUID), CAST(:productId AS UUID), :productName, CAST(:productNameI18n AS JSONB), CAST(:stationId AS UUID), :quantity, :basePrice, :optionsDelta, :unitPrice, :lineTotal, :note, :status, CAST(:optionsSnapshot AS JSONB))
                """
                jdbc.update(insertItemSql, mapOf(
                    "id" to item.id.toString(),
                    "orderId" to order.id.toString(),
                    "sessionId" to sessionId,
                    "productId" to item.snapshot.productId.toString(),
                    "productName" to item.snapshot.productName,
                    "productNameI18n" to objectMapper.writeValueAsString(item.snapshot.productNameI18n),
                    "stationId" to item.snapshot.stationId.toString(),
                    "quantity" to item.snapshot.quantity,
                    "basePrice" to item.snapshot.basePrice.minor,
                    "optionsDelta" to item.snapshot.optionsDelta.minor,
                    "unitPrice" to item.snapshot.unitPrice.minor,
                    "lineTotal" to item.snapshot.lineTotal.minor,
                    "note" to item.snapshot.note,
                    "status" to item.status.name,
                    "optionsSnapshot" to objectMapper.writeValueAsString(item.snapshot.options.map {
                        mapOf(
                            "groupId" to it.groupId.toString(),
                            "optionId" to it.optionId.toString(),
                            "groupName" to it.groupName,
                            "optionName" to it.optionName,
                            "priceDeltaMinor" to it.priceDelta.minor,
                        )
                    })
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

            val responseBody = linkedMapOf<String, Any>(
                "id" to order.id.toString(),
                "displayCode" to "#001",
                "status" to order.status.name,
                "version" to order.version,
                "createdBy" to mapOf(
                    "participantId" to participantId,
                    "displayName" to sessionAndParticipant.getValue("participantName"),
                ),
                "createdAt" to order.createdAt.toString(),
                "items" to order.items.map { item ->
                    linkedMapOf<String, Any>(
                        "id" to item.id.toString(),
                        "productId" to item.snapshot.productId.toString(),
                        "productName" to item.snapshot.productName,
                        "productNameI18n" to item.snapshot.productNameI18n,
                        "basePriceMinor" to item.snapshot.basePrice.minor,
                        "optionsDeltaMinor" to item.snapshot.optionsDelta.minor,
                        "unitPriceMinor" to item.snapshot.unitPrice.minor,
                        "quantity" to item.snapshot.quantity,
                        "lineTotalMinor" to item.snapshot.lineTotal.minor,
                        "station" to stationCodes.getValue(item.snapshot.stationId),
                        "status" to item.status.name,
                        "options" to item.snapshot.options.map {
                            mapOf(
                                "groupId" to it.groupId.toString(),
                                "optionId" to it.optionId.toString(),
                                "groupName" to it.groupName,
                                "optionName" to it.optionName,
                                "priceDeltaMinor" to it.priceDelta.minor,
                            )
                        },
                    ).apply { item.snapshot.note?.let { put("note", it) } }
                },
                "totalMinor" to totalMinor
            ).apply { request.note?.let { put("note", it) } }

            ResponseEntity.status(HttpStatus.CREATED).body(responseBody)
        }

    }

        private data class OptionGroupRule(
            val id: String,
            val name: String,
            val selectionType: String,
            val required: Boolean,
            val minSelections: Int,
            val maxSelections: Int,
        )

        private data class SelectedOption(
            val id: String,
            val groupId: String,
            val groupName: String,
            val name: String,
            val priceDeltaMinor: Long,
            val available: Boolean,
            val optionActive: Boolean,
            val groupActive: Boolean,
        )

        private data class SessionOrderRow(
            val id: UUID,
            val createdAt: Instant,
            val response: LinkedHashMap<String, Any>,
        )

        private fun validateAndSnapshotOptions(
            productId: String,
            restaurantId: String,
            requestedOptionIds: List<String>,
        ): List<OptionSnapshot> {
            if (requestedOptionIds.size > 10 || requestedOptionIds.size != requestedOptionIds.distinct().size) {
                throw DomainException(ErrorCode.OPTION_SELECTION_INVALID, "Invalid option selection")
            }
            val selectedIds = requestedOptionIds.map {
                try {
                    UUID.fromString(it)
                } catch (_: IllegalArgumentException) {
                    throw DomainException(ErrorCode.OPTION_NOT_FOUND, "Selected option does not exist", mapOf("optionIds" to requestedOptionIds))
                }
            }
            val groups = jdbc.query(
                """
                SELECT og.id, og.name, og.selection_type, og.required, og.min_selections, og.max_selections
                FROM option_group og
                JOIN product_option_group pog ON pog.group_id = og.id
                WHERE pog.product_id = CAST(:productId AS UUID)
                  AND og.restaurant_id = CAST(:restaurantId AS UUID)
                  AND og.active = TRUE
                """.trimIndent(),
                mapOf("productId" to productId, "restaurantId" to restaurantId),
            ) { rs, _ ->
                OptionGroupRule(
                    rs.getString("id"), rs.getString("name"), rs.getString("selection_type"),
                    rs.getBoolean("required"), rs.getInt("min_selections"), rs.getInt("max_selections"),
                )
            }
            val selected = if (selectedIds.isEmpty()) emptyList() else jdbc.query(
                """
                SELECT po.id, og.id AS group_id, og.name AS group_name, po.name, po.price_delta_minor,
                       po.available, po.active AS option_active, og.active AS group_active
                FROM product_option po
                JOIN option_group og ON og.id = po.group_id
                JOIN product_option_group pog ON pog.group_id = og.id
                WHERE pog.product_id = CAST(:productId AS UUID)
                  AND og.restaurant_id = CAST(:restaurantId AS UUID)
                  AND po.id IN (:optionIds)
                """.trimIndent(),
                mapOf("productId" to productId, "restaurantId" to restaurantId, "optionIds" to selectedIds),
            ) { rs, _ ->
                SelectedOption(
                    rs.getString("id"), rs.getString("group_id"), rs.getString("group_name"),
                    rs.getString("name"), rs.getLong("price_delta_minor"), rs.getBoolean("available"),
                    rs.getBoolean("option_active"), rs.getBoolean("group_active"),
                )
            }
            if (selected.size != selectedIds.size) {
                throw DomainException(ErrorCode.OPTION_NOT_FOUND, "Selected option does not belong to product", mapOf("optionIds" to requestedOptionIds))
            }
            val unavailable = selected.filterNot { it.available && it.optionActive && it.groupActive }.map { it.id }
            if (unavailable.isNotEmpty()) {
                throw DomainException(ErrorCode.OPTION_UNAVAILABLE, "Selected option is unavailable", mapOf("optionIds" to unavailable))
            }
            groups.forEach { group ->
                val count = selected.count { it.groupId == group.id }
                val valid = when (group.selectionType) {
                    "SINGLE" -> count <= 1 && count <= group.maxSelections &&
                        (!group.required || count >= group.minSelections)
                    "MULTIPLE" -> count in group.minSelections..group.maxSelections
                    else -> false
                }
                if (!valid) {
                    throw DomainException(
                        ErrorCode.OPTION_SELECTION_INVALID,
                        "Option group selection is invalid",
                        mapOf("groupId" to group.id, "min" to group.minSelections, "max" to group.maxSelections, "selected" to count),
                    )
                }
            }
            return selected.map {
                OptionSnapshot(UUID.fromString(it.groupId), UUID.fromString(it.id), it.groupName, it.name, Money.of(it.priceDeltaMinor))
            }
        }

    @GetMapping("/sessions/current/orders")
    fun listSessionOrders(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<Map<String, Any?>> {
        val sessionAndParticipant = resolveSessionAndParticipant(authorization)
        if (limit !in 1..100) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid limit")
        }
        val sessionId = UUID.fromString(sessionAndParticipant.getValue("sessionId"))
        val after = cursor?.let(OrderCursorCodec::decode)
        val params = mutableMapOf<String, Any>("sessionId" to sessionId, "limit" to limit + 1)
        val cursorFilter = if (after == null) "" else {
            params["cursorAt"] = java.sql.Timestamp.from(after.createdAt)
            params["cursorId"] = after.id
            "AND (o.created_at, o.id) < (:cursorAt, :cursorId)"
        }
        val rows = jdbc.query(
            """
            SELECT o.id, o.status, o.display_code, o.version, o.total_minor, o.note, o.created_at,
                   o.participant_id, p.display_name AS participant_name, o.rejection_reason, o.rejection_text
            FROM customer_order o
            JOIN session_participant p ON p.id = o.participant_id
            WHERE o.session_id = :sessionId $cursorFilter
            ORDER BY o.created_at DESC, o.id DESC
            LIMIT :limit
            """.trimIndent(),
            params,
        ) { rs, _ ->
            val id = rs.getObject("id", UUID::class.java)
            val createdAt = rs.getTimestamp("created_at").toInstant()
            val response = linkedMapOf<String, Any>(
                "id" to id.toString(),
                "status" to rs.getString("status"),
                "displayCode" to rs.getString("display_code"),
                "version" to rs.getLong("version"),
                "createdBy" to mapOf(
                    "participantId" to rs.getString("participant_id"),
                    "displayName" to rs.getString("participant_name"),
                ),
                "createdAt" to createdAt.toString(),
                "items" to emptyList<Any>(),
                "totalMinor" to rs.getLong("total_minor"),
            ).apply {
                rs.getString("note")?.let { put("note", it) }
                rs.getString("rejection_reason")?.let {
                    put("rejection", linkedMapOf("reasonCode" to it).apply {
                        rs.getString("rejection_text")?.let { text -> put("reasonText", text) }
                    })
                }
            }
            SessionOrderRow(id, createdAt, response)
        }

        val hasMore = rows.size > limit
        val page = rows.take(limit)
        if (page.isNotEmpty()) {
            val itemsByOrder = loadOrderItems(page.map { it.id })
            page.forEach { it.response["items"] = itemsByOrder[it.id].orEmpty() }
        }
        val nextCursor = if (hasMore) page.lastOrNull()?.let { OrderCursorCodec.encode(it.createdAt, it.id) } else null
        return ResponseEntity.ok(mapOf("items" to page.map { it.response }, "nextCursor" to nextCursor))
    }

    private fun loadOrderItems(orderIds: List<UUID>): Map<UUID, List<Map<String, Any>>> {
        if (orderIds.isEmpty()) return emptyMap()
        val itemRows = jdbc.query(
            """
            SELECT oi.order_id, oi.id, oi.product_id, oi.product_name, oi.product_name_i18n::text AS product_name_i18n,
                   oi.station_id, s.code AS station_code, oi.quantity, oi.note, oi.base_price_minor,
                   oi.options_delta_minor, oi.unit_price_minor, oi.line_total_minor, oi.status,
                   oi.queued_at, oi.started_at, oi.ready_at, oi.delivered_at, oi.cancelled_at,
                   oi.options_snapshot::text AS options_snapshot
            FROM order_item oi
            JOIN station s ON s.id = oi.station_id
            WHERE oi.order_id IN (:orderIds)
            ORDER BY oi.order_id, oi.id
            """.trimIndent(),
            mapOf("orderIds" to orderIds),
        ) { rs, _ ->
            val item = linkedMapOf<String, Any>(
                "id" to rs.getString("id"),
                "productId" to rs.getString("product_id"),
                "productName" to rs.getString("product_name"),
                "productNameI18n" to objectMapper.readValue(rs.getString("product_name_i18n") ?: "{}", MAP_TYPE),
                "basePriceMinor" to rs.getLong("base_price_minor"),
                "optionsDeltaMinor" to rs.getLong("options_delta_minor"),
                "unitPriceMinor" to rs.getLong("unit_price_minor"),
                "quantity" to rs.getInt("quantity"),
                "lineTotalMinor" to rs.getLong("line_total_minor"),
                "station" to rs.getString("station_code"),
                "status" to rs.getString("status"),
                "options" to objectMapper.readValue(
                    rs.getString("options_snapshot") ?: "[]",
                    OPTIONS_TYPE,
                ),
            ).apply {
                rs.getString("note")?.let { put("note", it) }
                // Los NULL históricos se omiten: la lectura no reconstruye horas que nunca se guardaron.
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
        return itemRows.groupBy({ it.first }, { it.second })
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
            throw DomainException(ErrorCode.ORDER_NOT_FOUND, "Order not found")
        }

        // Filtrar por sesión también en SQL evita revelar si el UUID existe en otra sesión.
        val order = jdbc.query(
            """
            SELECT o.id, o.status, o.display_code, o.version, o.total_minor, o.note, o.created_at,
                   o.participant_id, p.display_name AS participant_name, o.rejection_reason, o.rejection_text
            FROM customer_order o
            JOIN session_participant p ON p.id = o.participant_id
            WHERE o.id = :id AND o.session_id = :sessionId
            """.trimIndent(),
            mapOf("id" to uuid, "sessionId" to UUID.fromString(sessionAndParticipant.getValue("sessionId"))),
        ) { rs, _ ->
            linkedMapOf<String, Any>(
                "id" to rs.getString("id"),
                "status" to rs.getString("status"),
                "displayCode" to rs.getString("display_code"),
                "version" to rs.getLong("version"),
                "createdBy" to mapOf(
                    "participantId" to rs.getString("participant_id"),
                    "displayName" to rs.getString("participant_name"),
                ),
                "createdAt" to rs.getTimestamp("created_at").toInstant().toString(),
                "items" to emptyList<Any>(),
                "totalMinor" to rs.getLong("total_minor"),
            ).apply {
                rs.getString("note")?.let { put("note", it) }
                rs.getString("rejection_reason")?.let {
                    put("rejection", linkedMapOf("reasonCode" to it).apply {
                        rs.getString("rejection_text")?.let { text -> put("reasonText", text) }
                    })
                }
            }
        }.firstOrNull() ?: throw DomainException(ErrorCode.ORDER_NOT_FOUND, "Order not found")
        order["items"] = loadOrderItems(listOf(uuid))[uuid].orEmpty()
        return ResponseEntity.ok(order)
    }

    @PostMapping("/orders/{orderId}/cancel")
    @Transactional
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

        val now = java.sql.Timestamp.from(clock.instant())
        val updated = jdbc.update(
            "UPDATE customer_order SET status = 'CANCELLED', decided_at = :now, version = version + 1 WHERE id = CAST(:id AS UUID) AND session_id = CAST(:sessionId AS UUID) AND status = 'PENDING_REVIEW'",
            mapOf("id" to uuid.toString(), "sessionId" to sessionId, "now" to now)
        )

        if (updated == 0) {
            throw DomainException(ErrorCode.ORDER_NOT_CANCELLABLE, "Order cannot be cancelled")
        }
        jdbc.update(
            "UPDATE order_item SET status = 'CANCELLED', cancelled_at = :now WHERE order_id = :orderId AND status = 'PENDING'",
            mapOf("now" to now, "orderId" to uuid),
        )
        auditService.record(
            restaurantId = sessionAndParticipant.getValue("restaurantId"),
            actorType = "CUSTOMER",
            actorId = participantId,
            action = "ORDER_CANCELLED",
            targetType = "ORDER",
            targetId = uuid.toString(),
        )

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
                   ts.closed_at, sp.id AS participant_id, sp.display_name AS participant_name, sp.role, sp.revoked_at
            FROM table_session ts
            JOIN session_participant sp ON ts.id = sp.session_id
            WHERE sp.token_hash = :tokenHash
            """.trimIndent(),
            mapOf("tokenHash" to tokenHash),
        ) { rs, _ ->
            mapOf(
                "sessionId" to rs.getString("session_id"),
                "participantId" to rs.getString("participant_id"),
                "participantName" to rs.getString("participant_name"),
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

    private fun fetchProduct(productId: String, restaurantId: String): ProductRecord {
        val uuid = try {
            UUID.fromString(productId)
        } catch (e: Exception) {
            throw DomainException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found")
        }

        val product = jdbc.query(
            """
            SELECT p.id, p.name, p.name_i18n::text AS name_i18n, p.price_minor, p.station_id,
                   s.code AS station_code, p.available, p.active, c.active AS category_active
            FROM product p
            JOIN subcategory sub ON sub.id = p.subcategory_id
            JOIN category c ON c.id = sub.category_id
            JOIN station s ON s.id = p.station_id AND s.restaurant_id = c.restaurant_id
            WHERE p.id = CAST(:id AS UUID) AND c.restaurant_id = CAST(:restaurantId AS UUID)
            """.trimIndent(),
            mapOf("id" to uuid.toString(), "restaurantId" to restaurantId)
        ) { rs, _ ->
            ProductRecord(
                id = rs.getString("id"),
                name = rs.getString("name"),
                nameI18n = objectMapper.readValue(rs.getString("name_i18n") ?: "{}", stringMapType),
                priceMinor = rs.getLong("price_minor"),
                stationId = rs.getString("station_id"),
                stationCode = rs.getString("station_code"),
                available = rs.getBoolean("available") && rs.getBoolean("active") &&
                    rs.getBoolean("category_active"),
            )
        }.firstOrNull()

        return product ?: throw DomainException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found")
    }

    private companion object {
        const val CLOSED_SESSION_READ_SECONDS = 24 * 60 * 60L
        val stringMapType = object : tools.jackson.core.type.TypeReference<Map<String, String>>() {}
        val MAP_TYPE = object : TypeReference<Map<String, String>>() {}
        val OPTIONS_TYPE = object : TypeReference<List<Map<String, Any>>>() {}
    }
}
