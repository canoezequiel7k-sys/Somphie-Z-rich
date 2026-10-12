package com.somphie.orders.domain

import com.somphie.shared.domain.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Pruebas del snapshot de una línea (RN-ORD-04, RN-OPT-04, RN-BIL-01). */
class ItemSnapshotTest {

    private fun option(delta: Long) = OptionSnapshot("Leche", "Avena", Money.of(delta))

    @Test
    fun `without options the unit price is the base price`() {
        val snapshot = sampleSnapshot(quantity = 2)
        assertEquals(Money.ZERO, snapshot.optionsDelta)
        assertEquals(Money.of(485_000), snapshot.unitPrice)
        assertEquals(Money.of(970_000), snapshot.lineTotal)
    }

    @Test
    fun `options add their deltas to the unit price`() {
        val snapshot = sampleSnapshot(quantity = 1, options = listOf(option(30_000), option(20_000)))
        assertEquals(Money.of(50_000), snapshot.optionsDelta)
        assertEquals(Money.of(535_000), snapshot.unitPrice)
    }

    @Test
    fun `the line total multiplies the unit price WITH options by the quantity`() {
        val snapshot = sampleSnapshot(quantity = 3, options = listOf(option(30_000), option(20_000)))
        assertEquals(Money.of(1_605_000), snapshot.lineTotal)
    }

    @Test
    fun `an option with a zero delta does not change the price`() {
        val snapshot = sampleSnapshot(options = listOf(option(0)))
        assertEquals(Money.of(485_000), snapshot.unitPrice)
    }

    @Test
    fun `a quantity below one is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) { sampleSnapshot(quantity = 0) }
        assertThrows(IllegalArgumentException::class.java) { sampleSnapshot(quantity = -1) }
    }

    @Test
    fun `an item exposes the line total of its snapshot`() {
        val item = OrderItem.pending(java.util.UUID.randomUUID(), sampleSnapshot(quantity = 2))
        assertEquals(Money.of(970_000), item.lineTotal)
    }

    @Test
    fun `every transition keeps the snapshot unchanged`() {
        val snapshot = sampleSnapshot(quantity = 2, options = listOf(option(30_000)))
        val delivered = OrderItem.pending(java.util.UUID.randomUUID(), snapshot)
            .queue(T0).start(T0).markReady(T0).deliver(T0)
        assertEquals(snapshot, delivered.snapshot)
    }
}
