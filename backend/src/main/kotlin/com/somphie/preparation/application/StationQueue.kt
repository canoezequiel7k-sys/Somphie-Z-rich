package com.somphie.preparation.application

import com.fasterxml.jackson.annotation.JsonInclude
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

data class StationListEntry(
    val id: UUID,
    val code: String,
    val name: String,
)

data class StationQueueCursor(val acceptedAt: Instant?, val itemId: UUID)

data class StationQueueRecord(
    val itemId: UUID,
    val orderId: UUID,
    val orderCode: String,
    val tableLabel: String,
    val productName: String,
    val quantity: Int,
    val note: String?,
    val status: String,
    val acceptedAt: Instant?,
    val queuedAt: Instant?,
    val delayedSlaMinutes: Long,
    val options: List<QueueOption> = emptyList(),
)

data class QueueOption(
    val groupId: UUID,
    val optionId: UUID,
    val groupName: String,
    val optionName: String,
    val priceDeltaMinor: Long,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class QueueOptionResponse(
    val groupId: UUID,
    val optionId: UUID,
    val groupName: String,
    val optionName: String,
    val priceDeltaMinor: Long?,
)

interface StationQueueRepository {
    fun stationBelongsToRestaurant(stationId: UUID, restaurantId: UUID): Boolean

    fun findStations(restaurantId: UUID, assignedStationIds: Set<UUID>?): List<StationListEntry>

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
    val queuedAt: Instant?,
    val delayed: Boolean?,
    val optionsSummary: String,
    val options: List<QueueOptionResponse>,
)

data class StationQueueResponse(
    val items: List<StationQueueItemResponse>,
    val nextCursor: String?,
)

@Service
class GetAssignedStations(
    private val repository: StationQueueRepository,
) {
    fun execute(access: StationQueueAccess): Map<String, List<StationListEntry>> {
        if (PREPARE_PERMISSION !in access.permissions) {
            throw DomainException(ErrorCode.FORBIDDEN, "Staff permission is required")
        }
        val assignedIds = if ("OWNER" in access.roles) null else access.stationIds
        return mapOf("items" to repository.findStations(access.restaurantId, assignedIds))
    }

    private companion object {
        const val PREPARE_PERMISSION = "item.prepare"
    }
}

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
            throw DomainException(ErrorCode.ITEM_STATION_FORBIDDEN, "Station is not assigned to this account")
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
        val pageRows = rows.take(limit)
        val isKitchen = "KITCHEN" in access.roles && "OWNER" !in access.roles
        val page = pageRows.map { row ->
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
                // Sin acceptedAt no hay base legítima para afirmar que el SLA se cumplió o se excedió.
                delayed = row.acceptedAt?.let {
                    Duration.between(it, clock.instant()) > Duration.ofMinutes(row.delayedSlaMinutes)
                },
                optionsSummary = row.options.joinToString(" · ") { it.optionName },
                options = row.options.map { option ->
                    QueueOptionResponse(
                        groupId = option.groupId,
                        optionId = option.optionId,
                        groupName = option.groupName,
                        optionName = option.optionName,
                        priceDeltaMinor = option.priceDeltaMinor.takeUnless { isKitchen },
                    )
                },
            )
        }
        val nextCursor = if (rows.size > limit) {
            pageRows.lastOrNull()?.let { encodeCursor(StationQueueCursor(it.acceptedAt, it.itemId)) }
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
        if (parts.size != 3 || parts[0] != CURSOR_VERSION) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid queue cursor")
        }
        val acceptedAt = if (parts[1] == NULL_CURSOR_TIMESTAMP) null else try {
            Instant.parse(parts[1])
        } catch (_: DateTimeException) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid queue cursor")
        }
        val itemId = parseUuidForCursor(parts[2])
        return StationQueueCursor(acceptedAt, itemId)
    }

    private fun parseUuidForCursor(value: String): UUID = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid queue cursor")
    }

    private fun encodeCursor(cursor: StationQueueCursor): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(
                "$CURSOR_VERSION|${cursor.acceptedAt ?: NULL_CURSOR_TIMESTAMP}|${cursor.itemId}"
                    .toByteArray(StandardCharsets.UTF_8),
            )

    private companion object {
        const val PREPARE_PERMISSION = "item.prepare"
        const val MAX_LIMIT = 100
        const val CURSOR_VERSION = "v2"
        const val NULL_CURSOR_TIMESTAMP = "null"
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
