package com.somphie.preparation.application

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class GetStationQueueTest {
    private val restaurantId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val stationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
    private val now = Instant.parse("2026-10-12T12:00:00Z")
    private val repository = FakeStationQueueRepository(stationId, restaurantId, listOf(
        row("30000000-0000-0000-0000-000000000001", "QUEUED", now.minusSeconds(660)),
        row("30000000-0000-0000-0000-000000000002", "IN_PREPARATION", now.minusSeconds(60)),
    ))
    private val service = GetStationQueue(repository, Clock.fixed(now, ZoneOffset.UTC))
    private val access = StationQueueAccess(
        restaurantId = restaurantId,
        roles = setOf("KITCHEN"),
        permissions = setOf("item.prepare"),
        stationIds = setOf(stationId),
    )

    @Test
    fun `queue uses FIFO cursor and marks items delayed after configured SLA`() {
        val first = service.execute(access, stationId.toString(), null, 1, null)

        assertEquals(1, first.items.size)
        assertEquals("QUEUED", first.items.single().status)
        assertTrue(first.items.single().delayed)
        val cursor = first.nextCursor
        assertTrue(cursor != null)

        val second = service.execute(access, stationId.toString(), null, 1, cursor)
        assertEquals("IN_PREPARATION", second.items.single().status)
        assertEquals(null, second.nextCursor)
    }

    @Test
    fun `queue rejects staff without preparation permission`() {
        val exception = assertThrows<DomainException> {
            service.execute(access.copy(permissions = emptySet()), stationId.toString(), null, 50, null)
        }

        assertEquals(ErrorCode.FORBIDDEN, exception.code)
    }

    @Test
    fun `queue rejects a station outside the staff assignment`() {
        val exception = assertThrows<DomainException> {
            service.execute(access.copy(stationIds = emptySet()), stationId.toString(), null, 50, null)
        }

        assertEquals(ErrorCode.ITEM_STATION_FORBIDDEN, exception.code)
    }

    @Test
    fun `queue rejects invalid status and cursor`() {
        val invalidStatus = assertThrows<DomainException> {
            service.execute(access, stationId.toString(), listOf("UNKNOWN"), 50, null)
        }
        val invalidCursor = assertThrows<DomainException> {
            service.execute(access, stationId.toString(), null, 50, "not-a-cursor")
        }

        assertEquals(ErrorCode.VALIDATION_FAILED, invalidStatus.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, invalidCursor.code)
    }

    @Test
    fun `station list is scoped to assignments except for owners`() {
        val listStations = GetAssignedStations(repository)

        assertEquals(
            listOf(StationListEntry(stationId, "BAR", "Barra")),
            listStations.execute(access)["items"],
        )
        assertEquals(
            listOf(StationListEntry(stationId, "BAR", "Barra")),
            listStations.execute(access.copy(roles = setOf("OWNER"), stationIds = emptySet()))["items"],
        )
        assertEquals(
            emptyList<StationListEntry>(),
            listStations.execute(access.copy(restaurantId = UUID.randomUUID()))["items"],
        )
        val forbidden = assertThrows<DomainException> {
            listStations.execute(access.copy(permissions = emptySet()))
        }
        assertEquals(ErrorCode.FORBIDDEN, forbidden.code)
    }

    private fun row(id: String, status: String, queuedAt: Instant) = StationQueueRecord(
        itemId = UUID.fromString(id),
        orderId = UUID.fromString("40000000-0000-0000-0000-000000000001"),
        orderCode = "#001",
        tableLabel = "Mesa 1",
        productName = "Flat white",
        quantity = 1,
        note = null,
        status = status,
        queuedAt = queuedAt,
        delayedSlaMinutes = 10,
    )

    private class FakeStationQueueRepository(
        private val stationId: UUID,
        private val restaurantId: UUID,
        private val rows: List<StationQueueRecord>,
    ) : StationQueueRepository {
        override fun stationBelongsToRestaurant(stationId: UUID, restaurantId: UUID): Boolean =
            this.stationId == stationId && this.restaurantId == restaurantId

        override fun findStations(restaurantId: UUID, assignedStationIds: Set<UUID>?): List<StationListEntry> =
            if (restaurantId == this.restaurantId &&
                (assignedStationIds == null || this.stationId in assignedStationIds)
            ) {
                listOf(StationListEntry(this.stationId, "BAR", "Barra"))
            } else {
                emptyList()
            }

        override fun findQueueItems(
            restaurantId: UUID,
            stationId: UUID,
            statuses: List<String>,
            after: StationQueueCursor?,
            limit: Int,
        ): List<StationQueueRecord> = rows
            .filter { it.status in statuses }
            .filter { after == null || it.queuedAt > after.queuedAt || it.queuedAt == after.queuedAt && it.itemId > after.itemId }
            .take(limit)
    }
}
