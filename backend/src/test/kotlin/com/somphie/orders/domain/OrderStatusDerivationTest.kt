package com.somphie.orders.domain

import com.somphie.orders.domain.ItemStatus.CANCELLED
import com.somphie.orders.domain.ItemStatus.DELIVERED
import com.somphie.orders.domain.ItemStatus.IN_PREPARATION
import com.somphie.orders.domain.ItemStatus.PENDING
import com.somphie.orders.domain.ItemStatus.QUEUED
import com.somphie.orders.domain.ItemStatus.READY
import com.somphie.shared.domain.DomainException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Pruebas de `estados-y-transiciones.md` §4 (derivación del estado del pedido).
 * Tres niveles: ejemplos del documento, todas las combinaciones pequeñas, y todas las secuencias
 * alcanzables con transiciones reales (las "pruebas de propiedades" que pide el documento).
 */
class OrderStatusDerivationTest {

    private val statesAfterAcceptance = listOf(QUEUED, IN_PREPARATION, READY, DELIVERED, CANCELLED)

    @TestFactory
    fun `examples from the document`(): List<DynamicTest> = listOf(
        listOf(CANCELLED, CANCELLED) to OrderStatus.CANCELLED,
        listOf(DELIVERED, DELIVERED) to OrderStatus.DELIVERED,
        listOf(DELIVERED, CANCELLED) to OrderStatus.DELIVERED,
        listOf(READY, READY) to OrderStatus.READY,
        listOf(READY, DELIVERED) to OrderStatus.READY,
        listOf(READY, CANCELLED) to OrderStatus.READY,
        listOf(QUEUED, QUEUED) to OrderStatus.ACCEPTED,
        listOf(QUEUED, CANCELLED) to OrderStatus.ACCEPTED,
        listOf(QUEUED, IN_PREPARATION) to OrderStatus.IN_PREPARATION,
        listOf(QUEUED, READY) to OrderStatus.IN_PREPARATION,
        listOf(QUEUED, DELIVERED) to OrderStatus.IN_PREPARATION,
        listOf(IN_PREPARATION, READY) to OrderStatus.IN_PREPARATION,
        listOf(IN_PREPARATION, DELIVERED) to OrderStatus.IN_PREPARATION,
    ).map { (items, expected) ->
        dynamicTest("$items gives $expected") {
            assertEquals(expected, OrderStatusDerivation.fromItems(items))
        }
    }

    @Test
    fun `an empty collection counts as all items cancelled`() {
        assertEquals(OrderStatus.CANCELLED, OrderStatusDerivation.fromItems(emptyList()))
    }

    @Test
    fun `a pending item in an accepted order is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) {
            OrderStatusDerivation.fromItems(listOf(QUEUED, PENDING))
        }
    }

    @Test
    fun `the result is coherent for every combination of up to four items`() {
        val combinations = combinationsUpTo(size = 4)
        assertEquals(5 + 25 + 125 + 625, combinations.size)
        combinations.forEach { assertCoherent(it) }
    }

    @Test
    fun `the result does not depend on the order of the items`() {
        combinationsUpTo(size = 4).forEach {
            assertEquals(OrderStatusDerivation.fromItems(it), OrderStatusDerivation.fromItems(it.reversed()), "$it")
        }
    }

    @Test
    fun `every state reachable with real transitions derives a coherent order status`() {
        val start = List(3) { itemIn(QUEUED) }
        val seen = mutableMapOf(start.map { it.status } to start)
        val pending = ArrayDeque(listOf(start))

        while (pending.isNotEmpty()) {
            val items = pending.removeFirst()
            assertCoherent(items.map { it.status })
            for (index in items.indices) {
                for (action in ItemAction.entries) {
                    val next = try {
                        items[index].act(action)
                    } catch (e: DomainException) {
                        continue // transición inválida: no cambia nada, no genera estado nuevo
                    }
                    val state = items.toMutableList().also { it[index] = next }
                    if (seen.putIfAbsent(state.map { it.status }, state) == null) pending.add(state)
                }
            }
        }

        // 5 estados posibles por ítem, 3 ítems: la máquina real alcanza los 5^3 = 125.
        assertEquals(125, seen.size)
    }

    private fun combinationsUpTo(size: Int): List<List<ItemStatus>> {
        var current: List<List<ItemStatus>> = listOf(emptyList())
        val all = mutableListOf<List<ItemStatus>>()
        repeat(size) {
            current = current.flatMap { prefix -> statesAfterAcceptance.map { prefix + it } }
            all += current
        }
        return all
    }

    /** Invariantes de §4: es la regla "nadie marca terminado un pedido con ítems sin terminar". */
    private fun assertCoherent(items: List<ItemStatus>) {
        val active = items.filter { it != CANCELLED }
        val result = OrderStatusDerivation.fromItems(items)
        val allFinishedPreparing = active.all { it == READY || it == DELIVERED }
        when (result) {
            OrderStatus.CANCELLED -> assertTrue(active.isEmpty(), "$items")
            OrderStatus.DELIVERED -> assertTrue(active.isNotEmpty() && active.all { it == DELIVERED }, "$items")
            OrderStatus.READY -> assertTrue(allFinishedPreparing && active.any { it == READY }, "$items")
            OrderStatus.IN_PREPARATION -> assertTrue(
                !allFinishedPreparing && active.any { it != QUEUED },
                "$items",
            )
            OrderStatus.ACCEPTED -> assertTrue(active.isNotEmpty() && active.all { it == QUEUED }, "$items")
            else -> fail("Derivation must not produce $result for $items")
        }
    }
}
