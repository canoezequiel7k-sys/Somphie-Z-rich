package com.somphie.preparation.application

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

class GetStationQueueTest {
    private val restaurantId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val stationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
    private val now = Instant.parse("2026-10-12T12:00:00Z")
    private val repository = FakeStationQueueRepository(stationId, restaurantId, mutableListOf(
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
        assertEquals(true, first.items.single().delayed)
        val cursor = first.nextCursor
        assertTrue(cursor != null)

        val second = service.execute(access, stationId.toString(), null, 1, cursor)
        assertEquals("IN_PREPARATION", second.items.single().status)
        assertEquals(null, second.nextCursor)
    }

    @Test
    fun `queue uses accepted time for fifo and SLA without inventing historical timestamps`() {
        val earlierAcceptedAt = now.minusSeconds(900)
        val historicalQueuedAt = now.minusSeconds(30)
        val repository = FakeStationQueueRepository(
            stationId,
            restaurantId,
            mutableListOf(
                row("30000000-0000-0000-0000-000000000003", "QUEUED", null, earlierAcceptedAt),
                row("30000000-0000-0000-0000-000000000002", "QUEUED", null, null),
                row("30000000-0000-0000-0000-000000000001", "QUEUED", historicalQueuedAt, null),
            ),
        )
        val queue = GetStationQueue(repository, Clock.fixed(now, ZoneOffset.UTC))
        val firstPage = queue.execute(access, stationId.toString(), null, 2, null)

        assertEquals(
            listOf(
                "30000000-0000-0000-0000-000000000003",
                "30000000-0000-0000-0000-000000000001",
            ),
            firstPage.items.map { it.itemId.toString() },
        )
        assertEquals(null, firstPage.items.first().queuedAt)
        assertEquals(true, firstPage.items.first().delayed)
        assertEquals(historicalQueuedAt, firstPage.items[1].queuedAt)
        assertEquals(null, firstPage.items[1].delayed)

        val secondPage = queue.execute(access, stationId.toString(), null, 2, firstPage.nextCursor)
        assertEquals("30000000-0000-0000-0000-000000000002", secondPage.items.single().itemId.toString())
        assertEquals(null, secondPage.items.single().queuedAt)
        assertEquals(null, secondPage.items.single().delayed)
    }

    @Test
    fun `queue evaluates SLA with a strict boundary`() {
        val repository = FakeStationQueueRepository(
            stationId,
            restaurantId,
            mutableListOf(
                row("30000000-0000-0000-0000-000000000001", "QUEUED", now.minusSeconds(600)),
                row("30000000-0000-0000-0000-000000000002", "QUEUED", now.minusSeconds(601)),
            ),
        )

        val page = GetStationQueue(repository, Clock.fixed(now, ZoneOffset.UTC))
            .execute(access, stationId.toString(), null, 2, null)

        assertTrue(page.items[0].delayed!!)
        assertFalse(page.items[1].delayed!!)
    }

    @Test
    fun `keyset pages reflect inserts and status changes between requests`() {
        val rows = mutableListOf(
            row("30000000-0000-0000-0000-000000000001", "QUEUED", now.minusSeconds(900)),
            row("30000000-0000-0000-0000-000000000002", "QUEUED", now.minusSeconds(800)),
            row("30000000-0000-0000-0000-000000000003", "QUEUED", now.minusSeconds(700)),
        )
        val repository = FakeStationQueueRepository(stationId, restaurantId, rows)
        val queue = GetStationQueue(repository, Clock.fixed(now, ZoneOffset.UTC))
        val first = queue.execute(access, stationId.toString(), null, 1, null)

        rows[1] = rows[1].copy(status = "READY")
        rows.add(row("30000000-0000-0000-0000-000000000005", "QUEUED", now.minusSeconds(1000)))
        rows.add(row("30000000-0000-0000-0000-000000000004", "QUEUED", now.minusSeconds(650)))
        val second = queue.execute(access, stationId.toString(), null, 1, first.nextCursor)
        val third = queue.execute(access, stationId.toString(), null, 1, second.nextCursor)

        assertEquals("30000000-0000-0000-0000-000000000003", second.items.single().itemId.toString())
        assertEquals("30000000-0000-0000-0000-000000000004", third.items.single().itemId.toString())
        assertFalse(listOf(first, second, third).flatMap { it.items }.any {
            it.itemId.toString() == "30000000-0000-0000-0000-000000000005"
        })
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
        val invalidLimit = assertThrows<DomainException> {
            service.execute(access, stationId.toString(), null, 101, null)
        }
        val cursorFromOldOrdering = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$now|30000000-0000-0000-0000-000000000001".toByteArray())
        val staleCursor = assertThrows<DomainException> {
            service.execute(access, stationId.toString(), null, 50, cursorFromOldOrdering)
        }

        assertEquals(ErrorCode.VALIDATION_FAILED, invalidStatus.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, invalidCursor.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, invalidLimit.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, staleCursor.code)
        val zeroLimit = assertThrows<DomainException> {
            service.execute(access, stationId.toString(), null, 0, null)
        }
        assertEquals(ErrorCode.VALIDATION_FAILED, zeroLimit.code)
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

    private fun row(
        id: String,
        status: String,
        queuedAt: Instant?,
        acceptedAt: Instant? = queuedAt,
    ) = StationQueueRecord(
        itemId = UUID.fromString(id),
        orderId = UUID.fromString("40000000-0000-0000-0000-000000000001"),
        orderCode = "#001",
        tableLabel = "Mesa 1",
        productName = "Flat white",
        quantity = 1,
        note = null,
        status = status,
        acceptedAt = acceptedAt,
        queuedAt = queuedAt,
        delayedSlaMinutes = 10,
    )

    private class FakeStationQueueRepository(
        private val stationId: UUID,
        private val restaurantId: UUID,
        private val rows: MutableList<StationQueueRecord>,
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
            .filter { row -> after == null || isAfter(row, after) }
            .sortedWith(compareBy<StationQueueRecord> { it.acceptedAt == null }.thenBy { it.acceptedAt }.thenBy { it.itemId })
            .take(limit)

        private fun isAfter(row: StationQueueRecord, cursor: StationQueueCursor): Boolean =
            when {
                cursor.acceptedAt == null ->
                    row.acceptedAt == null && row.itemId > cursor.itemId
                row.acceptedAt == null -> true
                row.acceptedAt > cursor.acceptedAt -> true
                row.acceptedAt < cursor.acceptedAt -> false
                else -> row.itemId > cursor.itemId
            }
    }
}
