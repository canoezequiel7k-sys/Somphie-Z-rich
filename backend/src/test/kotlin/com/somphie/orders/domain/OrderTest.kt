package com.somphie.orders.domain

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.Duration
import java.util.UUID

/** Pruebas del agregado `Order`: `estados-y-transiciones.md` §2 y §7, RN-ORD-05, 08 a 12 y 18. */
class OrderTest {

    private val ttl: Duration = Duration.ofMinutes(15)
    private val later = T0.plusSeconds(60)

    private fun items(count: Int = 2): List<OrderItem> =
        List(count) { OrderItem.pending(UUID.randomUUID(), sampleSnapshot()) }

    private fun newOrder(count: Int = 2): Order = Order.create(UUID.randomUUID(), items(count), T0)

    private fun statusesOf(order: Order): List<ItemStatus> = order.items.map { it.status }

    // ---------- crear ----------

    @Test
    fun `a new order is pending review with pending items`() {
        val order = newOrder()
        assertEquals(OrderStatus.PENDING_REVIEW, order.status)
        assertEquals(listOf(ItemStatus.PENDING, ItemStatus.PENDING), statusesOf(order))
        assertEquals(T0, order.createdAt)
        assertNull(order.acceptedAt)
        assertNull(order.decidedAt)
        assertNull(order.rejection)
    }

    @Test
    fun `an order without items is ORDER_EMPTY`() {
        val error = assertThrows(DomainException::class.java) { Order.create(UUID.randomUUID(), emptyList(), T0) }
        assertEquals(ErrorCode.ORDER_EMPTY, error.code)
    }

    @Test
    fun `items that are not pending or have repeated ids are programming errors`() {
        assertThrows(IllegalArgumentException::class.java) {
            Order.create(UUID.randomUUID(), listOf(itemIn(ItemStatus.QUEUED)), T0)
        }
        val item = OrderItem.pending(UUID.randomUUID(), sampleSnapshot())
        assertThrows(IllegalArgumentException::class.java) {
            Order.create(UUID.randomUUID(), listOf(item, item), T0)
        }
    }

    @Test
    fun `an order taken by the staff is born accepted (RN-ORD-18)`() {
        val order = Order.createAccepted(UUID.randomUUID(), items(), T0)
        assertEquals(OrderStatus.ACCEPTED, order.status)
        assertEquals(listOf(ItemStatus.QUEUED, ItemStatus.QUEUED), statusesOf(order))
        assertEquals(T0, order.acceptedAt)
    }

    // ---------- aceptar ----------

    @Test
    fun `accepting queues every item and records the time`() {
        val order = newOrder().accept(later)
        assertEquals(OrderStatus.ACCEPTED, order.status)
        assertEquals(listOf(ItemStatus.QUEUED, ItemStatus.QUEUED), statusesOf(order))
        assertTrue(order.items.all { it.queuedAt == later })
        assertEquals(later, order.acceptedAt)
        assertEquals(later, order.decidedAt)
    }

    @Test
    fun `accepting with exclusions cancels only the excluded items and they do not bill`() {
        val order = newOrder(3)
        val excluded = order.items[1]
        val accepted = order.accept(later, setOf(excluded.id))

        assertEquals(OrderStatus.ACCEPTED, accepted.status)
        assertEquals(listOf(ItemStatus.QUEUED, ItemStatus.CANCELLED, ItemStatus.QUEUED), statusesOf(accepted))
        assertEquals(later, accepted.items[1].cancelledAt)
    }

    @Test
    fun `excluding every item is ORDER_ALL_ITEMS_EXCLUDED and changes nothing`() {
        val order = newOrder(2)
        val error = assertThrows(DomainException::class.java) { order.accept(later, order.items.map { it.id }.toSet()) }
        assertEquals(ErrorCode.ORDER_ALL_ITEMS_EXCLUDED, error.code)
        assertEquals(OrderStatus.PENDING_REVIEW, order.status)
        assertEquals(listOf(ItemStatus.PENDING, ItemStatus.PENDING), statusesOf(order))
    }

    @Test
    fun `excluding the only item of an order is also ORDER_ALL_ITEMS_EXCLUDED`() {
        val order = newOrder(1)
        val error = assertThrows(DomainException::class.java) { order.accept(later, setOf(order.items[0].id)) }
        assertEquals(ErrorCode.ORDER_ALL_ITEMS_EXCLUDED, error.code)
    }

    @Test
    fun `excluding an item that is not in the order is ITEM_NOT_FOUND`() {
        val order = newOrder()
        val error = assertThrows(DomainException::class.java) { order.accept(later, setOf(UUID.randomUUID())) }
        assertEquals(ErrorCode.ITEM_NOT_FOUND, error.code)
        assertEquals(OrderStatus.PENDING_REVIEW, order.status)
    }

    // ---------- rechazar ----------

    @Test
    fun `rejecting keeps the reason and cancels the items`() {
        RejectionReason.entries.forEach { reason ->
            val order = newOrder().reject(later, Rejection(reason, "Sin stock de leche"))
            assertEquals(OrderStatus.REJECTED, order.status)
            assertEquals(listOf(ItemStatus.CANCELLED, ItemStatus.CANCELLED), statusesOf(order))
            assertEquals(Rejection(reason, "Sin stock de leche"), order.rejection)
            assertEquals(later, order.decidedAt)
            assertNull(order.acceptedAt)
        }
        assertEquals(4, RejectionReason.entries.size)
    }

    @Test
    fun `the rejection text accepts 140 characters and not 141`() {
        Rejection(RejectionReason.OTHER, "a".repeat(140))
        assertThrows(IllegalArgumentException::class.java) { Rejection(RejectionReason.OTHER, "a".repeat(141)) }
    }

    // ---------- cancelar y expirar ----------

    @Test
    fun `cancelling a pending order cancels its items`() {
        val order = newOrder().cancel(later)
        assertEquals(OrderStatus.CANCELLED, order.status)
        assertEquals(listOf(ItemStatus.CANCELLED, ItemStatus.CANCELLED), statusesOf(order))
        assertEquals(later, order.decidedAt)
    }

    @Test
    fun `an order expires exactly when the ttl is reached and not before`() {
        val order = newOrder()
        assertFalse(order.isDueForExpiry(T0.plus(ttl).minusSeconds(1), ttl))
        assertTrue(order.isDueForExpiry(T0.plus(ttl), ttl))

        val expired = order.expire(T0.plus(ttl), ttl)
        assertEquals(OrderStatus.EXPIRED, expired.status)
        assertEquals(listOf(ItemStatus.CANCELLED, ItemStatus.CANCELLED), statusesOf(expired))
    }

    @Test
    fun `expiring an order that is not yet due is a programming error`() {
        val order = newOrder()
        assertThrows(IllegalArgumentException::class.java) { order.expire(T0.plus(ttl).minusSeconds(1), ttl) }
        assertEquals(OrderStatus.PENDING_REVIEW, order.status)
    }

    @Test
    fun `an order that was already decided is never due for expiry`() {
        assertFalse(newOrder().accept(later).isDueForExpiry(T0.plus(Duration.ofHours(1)), ttl))
    }

    // ---------- matriz de transiciones inválidas (§7) ----------

    private enum class Act { ACCEPT, REJECT, CANCEL, EXPIRE }

    private fun Order.act(act: Act): Order {
        val now = T0.plus(Duration.ofHours(1))
        return when (act) {
            Act.ACCEPT -> accept(now)
            Act.REJECT -> reject(now, Rejection(RejectionReason.OTHER))
            Act.CANCEL -> cancel(now)
            Act.EXPIRE -> expire(now, ttl)
        }
    }

    private fun orderIn(status: OrderStatus): Order = when (status) {
        OrderStatus.ACCEPTED -> newOrder().accept(T0)
        OrderStatus.REJECTED -> newOrder().reject(T0, Rejection(RejectionReason.OTHER))
        OrderStatus.CANCELLED -> newOrder().cancel(T0)
        OrderStatus.EXPIRED -> newOrder().expire(T0.plus(ttl), ttl)
        else -> error("Not reachable with the operations implemented so far: $status")
    }

    private fun expectedCode(status: OrderStatus, act: Act): ErrorCode = when {
        act == Act.CANCEL -> ErrorCode.ORDER_NOT_CANCELLABLE
        status == OrderStatus.EXPIRED && (act == Act.ACCEPT || act == Act.REJECT) -> ErrorCode.ORDER_EXPIRED
        else -> ErrorCode.ORDER_ALREADY_DECIDED
    }

    @TestFactory
    fun `a decided order rejects every decision with the catalog code and changes nothing`(): List<DynamicTest> =
        listOf(OrderStatus.ACCEPTED, OrderStatus.REJECTED, OrderStatus.CANCELLED, OrderStatus.EXPIRED).flatMap { status ->
            Act.entries.map { act ->
                dynamicTest("$status then $act") {
                    val order = orderIn(status)
                    val itemsBefore = statusesOf(order)
                    val error = assertThrows(DomainException::class.java) { order.act(act) }
                    assertEquals(expectedCode(status, act), error.code)
                    assertEquals(status, order.status, "a failed decision must not change the order")
                    assertEquals(itemsBefore, statusesOf(order))
                }
            }
        }

    @Test
    fun `ORDER_ALREADY_DECIDED reports the current status and when it was decided`() {
        val order = newOrder().accept(later)
        val error = assertThrows(DomainException::class.java) { order.reject(T0.plusSeconds(120), Rejection(RejectionReason.OTHER)) }
        assertEquals(ErrorCode.ORDER_ALREADY_DECIDED, error.code)
        assertEquals(OrderStatus.ACCEPTED, error.extra["currentStatus"])
        assertEquals(later, error.extra["decidedAt"])
    }

    @Test
    fun `the status of an accepted order comes from the derivation of its items`() {
        val order = newOrder(3)
        val accepted = order.accept(later, setOf(order.items[0].id))
        assertEquals(OrderStatusDerivation.fromItems(statusesOf(accepted)), accepted.status)
    }
}
