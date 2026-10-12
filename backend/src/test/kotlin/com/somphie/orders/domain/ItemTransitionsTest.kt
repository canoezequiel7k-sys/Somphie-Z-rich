package com.somphie.orders.domain

import com.somphie.orders.domain.ItemAction.CANCEL
import com.somphie.orders.domain.ItemAction.DELIVER
import com.somphie.orders.domain.ItemAction.MARK_READY
import com.somphie.orders.domain.ItemAction.START
import com.somphie.orders.domain.ItemAction.UNDO
import com.somphie.orders.domain.ItemStatus.CANCELLED
import com.somphie.orders.domain.ItemStatus.DELIVERED
import com.somphie.orders.domain.ItemStatus.IN_PREPARATION
import com.somphie.orders.domain.ItemStatus.PENDING
import com.somphie.orders.domain.ItemStatus.QUEUED
import com.somphie.orders.domain.ItemStatus.READY
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.Duration
import java.util.UUID

/**
 * Pruebas de `estados-y-transiciones.md` §3 y §7.
 *
 * La tabla `valid` está escrita A MANO a partir del documento, de forma independiente del código:
 * si alguien cambia una transición en OrderItem sin cambiar el documento, esta prueba falla.
 */
class ItemTransitionsTest {

    /** Transiciones válidas (estado actual + acción → estado nuevo). Todo lo demás debe fallar. */
    private val valid: Map<Pair<ItemStatus, ItemAction>, ItemStatus> = mapOf(
        (QUEUED to START) to IN_PREPARATION,
        (QUEUED to MARK_READY) to READY,
        (IN_PREPARATION to MARK_READY) to READY,
        (READY to DELIVER) to DELIVERED,
        (QUEUED to CANCEL) to CANCELLED,
        (IN_PREPARATION to CANCEL) to CANCELLED,
        (READY to CANCEL) to CANCELLED,
        (IN_PREPARATION to UNDO) to QUEUED,
        (READY to UNDO) to IN_PREPARATION,
        (DELIVERED to UNDO) to READY,
    )

    @TestFactory
    fun `every status and action combination behaves as the transition table says`(): List<DynamicTest> =
        ItemStatus.entries.flatMap { status ->
            ItemAction.entries.map { action ->
                dynamicTest("$status then $action") {
                    val item = itemIn(status)
                    val expected = valid[status to action]
                    if (expected != null) {
                        assertEquals(expected, item.act(action).status)
                    } else {
                        val expectedCode =
                            if (status == DELIVERED && action == CANCEL) ErrorCode.ITEM_NOT_CANCELLABLE
                            else ErrorCode.ITEM_INVALID_TRANSITION
                        val error = assertThrows(DomainException::class.java) { item.act(action) }
                        assertEquals(expectedCode, error.code)
                        assertEquals(status, item.status, "a failed transition must not change the item")
                    }
                }
            }
        }

    @TestFactory
    fun `queue and exclude only apply to pending items`(): List<DynamicTest> =
        ItemStatus.entries.flatMap { status ->
            listOf<Pair<String, (OrderItem) -> OrderItem>>(
                "queue" to { item: OrderItem -> item.queue(T0) },
                "exclude" to { item: OrderItem -> item.exclude(T0) },
            ).map { (name, op) ->
                dynamicTest("$name from $status") {
                    val item = itemIn(status)
                    if (status == PENDING) {
                        op(item)
                    } else {
                        val error = assertThrows(DomainException::class.java) { op(item) }
                        assertEquals(ErrorCode.ITEM_INVALID_TRANSITION, error.code)
                    }
                }
            }
        }

    @Test
    fun `queue moves a pending item to queued and exclude cancels it`() {
        val pending = OrderItem.pending(UUID.randomUUID())
        assertEquals(QUEUED, pending.queue(T0).status)
        assertEquals(CANCELLED, pending.exclude(T0).status)
        assertEquals(T0, pending.exclude(T0).cancelledAt)
    }

    @Test
    fun `an item starts pending with no timestamps`() {
        val item = OrderItem.pending(UUID.randomUUID())
        assertEquals(PENDING, item.status)
        listOf(item.queuedAt, item.startedAt, item.readyAt, item.deliveredAt, item.cancelledAt).forEach { assertNull(it) }
    }

    @Test
    fun `each transition records its timestamp`() {
        val t1 = T0.plusSeconds(60)
        val t2 = T0.plusSeconds(120)
        val t3 = T0.plusSeconds(180)
        val item = OrderItem.pending(UUID.randomUUID())
            .queue(T0).start(t1).markReady(t2).deliver(t3)
        assertEquals(T0, item.queuedAt)
        assertEquals(t1, item.startedAt)
        assertEquals(t2, item.readyAt)
        assertEquals(t3, item.deliveredAt)
    }

    @Test
    fun `undo clears only the timestamp of the state being left and keeps the queue position`() {
        val delivered = itemIn(DELIVERED)
        val ready = delivered.undo(T0, UNDO_WINDOW)
        assertNull(ready.deliveredAt)
        assertEquals(T0, ready.readyAt)

        val inPreparation = ready.undo(T0, UNDO_WINDOW)
        assertNull(inPreparation.readyAt)
        assertEquals(T0, inPreparation.startedAt)

        val queued = inPreparation.undo(T0, UNDO_WINDOW)
        assertNull(queued.startedAt)
        assertEquals(T0, queued.queuedAt, "FIFO position (queuedAt) must survive an undo")
    }

    @Test
    fun `undoing a delivery is allowed exactly at the window limit`() {
        val delivered = itemIn(DELIVERED, now = T0)
        val result = delivered.undo(T0.plus(UNDO_WINDOW), UNDO_WINDOW)
        assertEquals(READY, result.status)
    }

    @Test
    fun `undoing a delivery one second after the window fails and changes nothing`() {
        val delivered = itemIn(DELIVERED, now = T0)
        val late = T0.plus(UNDO_WINDOW).plus(Duration.ofSeconds(1))
        val error = assertThrows(DomainException::class.java) { delivered.undo(late, UNDO_WINDOW) }
        assertEquals(ErrorCode.ITEM_UNDO_WINDOW_EXPIRED, error.code)
        assertEquals(DELIVERED, delivered.status)
    }

    @Test
    fun `an invalid transition reports the current status`() {
        val error = assertThrows(DomainException::class.java) { itemIn(QUEUED).deliver(T0) }
        assertEquals(ErrorCode.ITEM_INVALID_TRANSITION, error.code)
        assertEquals(QUEUED, error.extra["currentStatus"])
    }
}
