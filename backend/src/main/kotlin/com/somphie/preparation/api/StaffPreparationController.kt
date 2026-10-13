package com.somphie.preparation.api

import com.somphie.identity.application.IdentityService
import com.somphie.identity.domain.StaffProfile
import com.somphie.orders.domain.ItemStatus
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

@RestController
class StaffPreparationController(
    private val jdbc: NamedParameterJdbcTemplate,
    private val identityService: IdentityService,
    private val clock: Clock,
) {
    @GetMapping("/staff/stations/{stationId}/queue")
    fun getStationQueue(
        @PathVariable stationId: String,
        @RequestParam(required = false) status: List<String>?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(required = false) cursor: String?,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Map<String, Any?>> {
        val profile = identityService.me(jwt.subject, jwt.getClaim("rid"), jwt.getClaim("tv"))
        requirePermission(profile, PREPARE_PERMISSION)
        if (limit !in 1..MAX_LIMIT) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and $MAX_LIMIT")
        }
        val stationUuid = parseUuid(stationId)
        requireStationAccess(profile, stationUuid)
        val statuses = parseStatuses(status)
        if (statuses.isEmpty()) return ResponseEntity.ok(mapOf("items" to emptyList<Any>(), "nextCursor" to null))
        val after = cursor?.let(::decodeCursor)
        val now = clock.instant()

        val params = mutableMapOf<String, Any>(
            "restaurantId" to profile.account.restaurantId,
            "stationId" to stationUuid,
            "statuses" to statuses.map(ItemStatus::name),
            "limit" to limit + 1,
            "now" to java.sql.Timestamp.from(now),
        )
        val cursorFilter = if (after == null) "" else {
            params["cursorAt"] = java.sql.Timestamp.from(after.queuedAt)
            params["cursorItemId"] = after.itemId
            "AND (o.accepted_at, oi.id) > (:cursorAt, :cursorItemId)"
        }
        val rows = jdbc.query(
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
            val queuedAt = rs.getTimestamp("accepted_at").toInstant()
            val itemId = rs.getObject("item_id", UUID::class.java)
            val item = linkedMapOf<String, Any>(
                "itemId" to itemId.toString(),
                "orderId" to rs.getObject("order_id", UUID::class.java).toString(),
                "orderCode" to rs.getString("display_code"),
                "tableLabel" to rs.getString("table_label"),
                "productName" to rs.getString("product_name"),
                "quantity" to rs.getInt("quantity"),
                "status" to rs.getString("status"),
                "queuedAt" to queuedAt.toString(),
                "delayed" to (
                    Duration.between(queuedAt, now) >
                        Duration.ofMinutes(rs.getLong("delayed_item_sla_min"))
                    ),
            )
            rs.getString("note")?.let { item["note"] = it }
            QueueRow(item, queuedAt, itemId)
        }

        val hasMore = rows.size > limit
        val page = rows.take(limit)
        val nextCursor = if (hasMore) page.lastOrNull()?.let { encodeCursor(it.queuedAt, it.itemId) } else null
        return ResponseEntity.ok(mapOf("items" to page.map(QueueRow::item), "nextCursor" to nextCursor))
    }

    private fun requirePermission(profile: StaffProfile, permission: String) {
        if (permission !in profile.permissions) {
            throw DomainException(ErrorCode.FORBIDDEN, "Staff permission is required")
        }
    }

    private fun requireStationAccess(profile: StaffProfile, stationId: UUID) {
        val stationExists = jdbc.query(
            """
            SELECT EXISTS (
                SELECT 1 FROM station
                WHERE id = :stationId AND restaurant_id = :restaurantId
            )
            """.trimIndent(),
            mapOf("stationId" to stationId, "restaurantId" to profile.account.restaurantId),
        ) { rs, _ -> rs.getBoolean(1) }.first()
        if (!stationExists) throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Station not found")
        if ("OWNER" !in profile.roles && profile.stations.none { it.id == stationId }) {
            throw DomainException(ErrorCode.FORBIDDEN, "Station is not assigned to this account")
        }
    }

    private fun parseStatuses(values: List<String>?): List<ItemStatus> {
        if (values.isNullOrEmpty()) return DEFAULT_STATUSES
        return values.map {
            try {
                ItemStatus.valueOf(it)
            } catch (_: IllegalArgumentException) {
                throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid item status")
            }
        }.distinct()
    }

    private fun parseUuid(value: String): UUID = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Station not found")
    }

    private fun decodeCursor(value: String): QueueCursor {
        val decoded = try {
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split('|')
        } catch (_: IllegalArgumentException) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid queue cursor")
        }
        if (decoded.size != 2) throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid queue cursor")
        val queuedAt = try {
            Instant.parse(decoded[0])
        } catch (_: DateTimeException) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid queue cursor")
        }
        val itemId = try {
            UUID.fromString(decoded[1])
        } catch (_: IllegalArgumentException) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid queue cursor")
        }
        return QueueCursor(queuedAt, itemId)
    }

    private fun encodeCursor(queuedAt: Instant, itemId: UUID): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$queuedAt|$itemId".toByteArray(StandardCharsets.UTF_8))

    private data class QueueCursor(val queuedAt: Instant, val itemId: UUID)
    private data class QueueRow(val item: Map<String, Any>, val queuedAt: Instant, val itemId: UUID)

    private companion object {
        const val PREPARE_PERMISSION = "item.prepare"
        const val MAX_LIMIT = 100
        val DEFAULT_STATUSES = listOf(ItemStatus.QUEUED, ItemStatus.IN_PREPARATION)
    }
}
