package com.somphie.orders.domain

import com.somphie.shared.domain.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

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

    // ---------- inmutabilidad real de las colecciones ----------

    private val fixedProductId: UUID = UUID.randomUUID()
    private val fixedStationId: UUID = UUID.randomUUID()

    private fun snapshotOf(options: List<OptionSnapshot> = emptyList(), i18n: Map<String, String> = emptyMap()) =
        ItemSnapshot(
            productId = fixedProductId,
            productName = "Café",
            productNameI18n = i18n,
            stationId = fixedStationId,
            quantity = 2,
            basePrice = Money.of(485_000),
            options = options,
        )

    @Test
    fun `changing the original options list afterwards does not change the snapshot or its prices`() {
        val source = mutableListOf(option(30_000))
        val snapshot = sampleSnapshot(quantity = 2, options = source)

        source.add(option(20_000))
        assertEquals(1, snapshot.options.size)
        assertEquals(Money.of(30_000), snapshot.optionsDelta)
        assertEquals(Money.of(1_030_000), snapshot.lineTotal)

        source.clear()
        assertEquals(1, snapshot.options.size)
        assertEquals(Money.of(1_030_000), snapshot.lineTotal)
    }

    @Test
    fun `changing the original translations map afterwards does not change the snapshot`() {
        val source = mutableMapOf("en" to "Coffee")
        val snapshot = snapshotOf(i18n = source)

        source["es"] = "Café"
        source.remove("en")
        assertEquals(mapOf("en" to "Coffee"), snapshot.productNameI18n)
    }

    @Test
    fun `the exposed collections cannot be modified even through a cast`() {
        val snapshot = snapshotOf(options = listOf(option(30_000)), i18n = mapOf("en" to "Coffee"))
        val options = snapshot.options as MutableList<OptionSnapshot>
        val i18n = snapshot.productNameI18n as MutableMap<String, String>

        assertThrows(UnsupportedOperationException::class.java) { options.add(option(1)) }
        assertThrows(UnsupportedOperationException::class.java) { options.clear() }
        assertThrows(UnsupportedOperationException::class.java) { i18n.put("es", "Café") }
        assertThrows(UnsupportedOperationException::class.java) { i18n.clear() }
        assertEquals(Money.of(1_030_000), snapshot.lineTotal)
    }

    @Test
    fun `snapshots with the same values are equal even if built from different collections`() {
        val a = snapshotOf(
            options = listOf(option(30_000)),
            i18n = mapOf("en" to "Coffee")
        )
        val b = snapshotOf(
            options = mutableListOf(option(30_000)),
            i18n = mutableMapOf("en" to "Coffee")
        )

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        assertNotEquals(
            a,
            snapshotOf(options = listOf(option(31_000)), i18n = mapOf("en" to "Coffee"))
        )
        assertNotEquals(
            a,
            snapshotOf(options = listOf(option(30_000)), i18n = emptyMap())
        )
    }

    @Test
    fun `the hash code does not change when the original collections are modified`() {
        val source = mutableListOf(option(30_000))
        val snapshot = snapshotOf(options = source)
        val before = snapshot.hashCode()

        source.add(option(1))

        assertEquals(before, snapshot.hashCode())
        assertEquals(snapshotOf(options = listOf(option(30_000))), snapshot)
    }

}