package com.somphie.preparation.application

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class StationQueueAccess(
    val restaurantId: UUID,
    val roles: Set<String>,
    val permissions: Set<String>,
    val stationIds: Set<UUID>,
)

data class StationQueueCursor(val queuedAt: Instant, val itemId: UUID)

data class StationQueueRecord(
    val itemId: UUID,
    val orderId: UUID,
    val orderCode: String,
    val tableLabel: String,
    val productName: String,
    val quantity: Int,
    val note: String?,
    val status: String,
    val queuedAt: Instant,
    val delayedSlaMinutes: Long,
)

interface StationQueueRepository {
    fun stationBelongsToRestaurant(stationId: UUID, restaurantId: UUID): Boolean

    fun findQueueItems(
        restaurantId: UUID,
        stationId: UUID,
        statuses: List<String>,
        after: StationQueueCursor?,
        limit: Int,
    ): List<StationQueueRecord>
}

data class StationQueueItemResponse(
    val itemId: UUID,
    val orderId: UUID,
    val orderCode: String,
    val tableLabel: String,
    val productName: String,
    val quantity: Int,
    val note: String?,
    val status: String,
    val queuedAt: Instant,
    val delayed: Boolean,
)

data class StationQueueResponse(
    val items: List<StationQueueItemResponse>,
    val nextCursor: String?,
)

@Service
class GetStationQueue(
    private val repository: StationQueueRepository,
    private val clock: Clock,
) {
    fun execute(
        access: StationQueueAccess,
        stationId: String,
        requestedStatuses: List<String>?,
        limit: Int,
        cursor: String?,
    ): StationQueueResponse {
        if (PREPARE_PERMISSION !in access.permissions) {
            throw DomainException(ErrorCode.FORBIDDEN, "Staff permission is required")
        }
        if (limit !in 1..MAX_LIMIT) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and $MAX_LIMIT")
        }
        val stationUuid = parseUuid(stationId)
        if (!repository.stationBelongsToRestaurant(stationUuid, access.restaurantId)) {
            throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Station not found")
        }
        if ("OWNER" !in access.roles && stationUuid !in access.stationIds) {
            throw DomainException(ErrorCode.FORBIDDEN, "Station is not assigned to this account")
        }
        val statuses = parseStatuses(requestedStatuses)
        if (statuses.isEmpty()) return StationQueueResponse(emptyList(), null)
        val after = cursor?.let(::decodeCursor)
        val rows = repository.findQueueItems(
            restaurantId = access.restaurantId,
            stationId = stationUuid,
            statuses = statuses,
            after = after,
            limit = limit + 1,
        )
        val page = rows.take(limit).map { row ->
            StationQueueItemResponse(
                itemId = row.itemId,
                orderId = row.orderId,
                orderCode = row.orderCode,
                tableLabel = row.tableLabel,
                productName = row.productName,
                quantity = row.quantity,
                note = row.note,
                status = row.status,
                queuedAt = row.queuedAt,
                delayed = Duration.between(row.queuedAt, clock.instant()) >
                    Duration.ofMinutes(row.delayedSlaMinutes),
            )
        }
        val nextCursor = if (rows.size > limit) {
            page.lastOrNull()?.let { encodeCursor(StationQueueCursor(it.queuedAt, it.itemId)) }
        } else {
            null
        }
        return StationQueueResponse(page, nextCursor)
    }

    private fun parseStatuses(values: List<String>?): List<String> {
        if (values.isNullOrEmpty()) return DEFAULT_STATUSES
        if (values.any { it !in ITEM_STATUSES }) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid item status")
        }
        return values.distinct()
    }

    private fun parseUuid(value: String): UUID = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Station not found")
    }

    private fun decodeCursor(value: String): StationQueueCursor {
        val parts = try {
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split('|')
        } catch (_: IllegalArgumentException) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid queue cursor")
        }
        if (parts.size != 2) throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid queue cursor")
        val queuedAt = try {
            Instant.parse(parts[0])
        } catch (_: DateTimeException) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid queue cursor")
        }
        val itemId = parseUuidForCursor(parts[1])
        return StationQueueCursor(queuedAt, itemId)
    }

    private fun parseUuidForCursor(value: String): UUID = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid queue cursor")
    }

    private fun encodeCursor(cursor: StationQueueCursor): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("${cursor.queuedAt}|${cursor.itemId}".toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val PREPARE_PERMISSION = "item.prepare"
        const val MAX_LIMIT = 100
        val DEFAULT_STATUSES = listOf("QUEUED", "IN_PREPARATION")
        val ITEM_STATUSES = setOf(
            "PENDING",
            "QUEUED",
            "IN_PREPARATION",
            "READY",
            "DELIVERED",
            "CANCELLED",
        )
    }
}
