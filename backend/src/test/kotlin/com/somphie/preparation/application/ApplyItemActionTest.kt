package com.somphie.preparation.application

import com.somphie.orders.domain.ItemSnapshot
import com.somphie.orders.domain.Order
import com.somphie.orders.domain.OrderItem
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.domain.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ApplyItemActionTest {
    private val now = Instant.parse("2026-10-12T12:00:00Z")
    private val restaurantId = UUID.randomUUID()
    private val stationId = UUID.randomUUID()
    private val itemIds = listOf(UUID.randomUUID(), UUID.randomUUID())
    private val baseOrder = Order.create(
        UUID.randomUUID(),
        itemIds.map {
            OrderItem.pending(
                it,
                ItemSnapshot(UUID.randomUUID(), "Café", stationId = stationId, quantity = 1, basePrice = Money.of(500)),
            )
        },
        now.minusSeconds(30),
    ).accept(now.minusSeconds(20))
    private val repository = FakeRepository(baseOrder)
    private val service = ApplyItemAction(repository, Clock.fixed(now, ZoneOffset.UTC))
    private val access = PreparationAccess(
        restaurantId, UUID.randomUUID(), "Cocina",
        setOf("KITCHEN"), setOf("item.prepare", "item.deliver", "item.undo", "order.cancel", "order.read"),
        setOf(stationId),
    )

    @Test
    fun `start updates timestamp and persists one aggregate version with audit`() {
        val result = service.execute(access, itemIds.first(), ItemAction.START)

        assertEquals(3L, repository.order.version)
        assertEquals(now, repository.order.items.first().startedAt)
        assertEquals("IN_PREPARATION", result["status"])
        assertEquals(1, repository.auditCount)
        assertEquals(2L, repository.lastExpectedVersion)
    }

    @Test
    fun `deliver ready updates every ready item with one version increment and one audit per item`() {
        repository.order = baseOrder.items.fold(baseOrder) { current, item ->
            current.markItemReady(item.id, now.minusSeconds(1))
        }
        val before = repository.order.version

        service.deliverReady(access, baseOrder.id)

        assertEquals(before + 1, repository.order.version)
        assertEquals(2, repository.order.items.count { it.status.name == "DELIVERED" })
        assertEquals(2, repository.auditCount)
    }

    @Test
    fun `deliver ready with no ready items does not mutate version or audit`() {
        val version = repository.order.version

        service.deliverReady(access, repository.order.id)

        assertEquals(version, repository.order.version)
        assertEquals(0, repository.saveCount)
        assertEquals(0, repository.auditCount)
    }

    @Test
    fun `permission and invalid transition fail without persistence or audit`() {
        val forbidden = assertThrows(DomainException::class.java) {
            service.execute(access.copy(permissions = emptySet()), itemIds.first(), ItemAction.START)
        }
        assertEquals(ErrorCode.FORBIDDEN, forbidden.code)
        val invalid = assertThrows(DomainException::class.java) {
            service.execute(access, itemIds.first(), ItemAction.DELIVER)
        }
        assertEquals(ErrorCode.ITEM_INVALID_TRANSITION, invalid.code)
        assertEquals(2L, repository.order.version)
        assertEquals(0, repository.auditCount)
        assertEquals(0, repository.saveCount)
    }

    private class FakeRepository(var order: Order) : PreparationActionRepository {
        var saveCount = 0
        var auditCount = 0
        var lastExpectedVersion: Long? = null

        override fun lockForAction(restaurantId: UUID, itemId: UUID) =
            LockedPreparationOrder(order, true, emptySet())

        override fun lockOrderForAction(restaurantId: UUID, orderId: UUID) =
            if (orderId == order.id) LockedPreparationOrder(order, true, emptySet()) else null

        override fun save(
            restaurantId: UUID,
            order: Order,
            expectedVersion: Long,
            cancelledItemId: UUID?,
            cancellationReason: String?,
        ): Boolean {
            saveCount++
            lastExpectedVersion = expectedVersion
            this.order = order
            return true
        }

        override fun staffOrder(access: PreparationAccess, orderId: UUID): Map<String, Any> =
            mapOf("id" to orderId.toString(), "status" to order.status.name, "version" to order.version)

        override fun undoWindowMinutes(restaurantId: UUID) = 5L

        override fun recordAudit(
            access: PreparationAccess,
            itemId: UUID,
            action: ItemAction,
            beforeStatus: String,
            afterStatus: String,
            reason: String?,
        ) {
            auditCount++
        }
    }
}
