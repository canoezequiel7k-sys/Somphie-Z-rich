package com.somphie.shared.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

/** Pruebas de [Money] (ADR-0004: dinero en centavos, sin decimales flotantes). */
class MoneyTest {

    @Test
    fun `zero and positive amounts are valid`() {
        assertEquals(0L, Money.ZERO.minor)
        assertEquals(485_000L, Money.of(485_000).minor)
    }

    @Test
    fun `a negative amount is rejected`() {
        // Un Money negativo es un bug de programación: lanza IllegalArgumentException.
        assertThrows(IllegalArgumentException::class.java) { Money.of(-1) }
    }

    @Test
    fun `plus adds cents exactly`() {
        // Con Double, 0.1 + 0.2 da 0.30000000000000004. Con centavos enteros, 10 + 20 es siempre 30.
        assertEquals(Money.of(30), Money.of(10) + Money.of(20))
    }

    @Test
    fun `times multiplies a unit price by a quantity`() {
        assertEquals(Money.of(970_000), Money.of(485_000) * 2)
        assertEquals(Money.ZERO, Money.of(485_000) * 0)
    }

    @Test
    fun `times rejects a negative quantity`() {
        assertThrows(IllegalArgumentException::class.java) { Money.of(100) * -1 }
    }

    @Test
    fun `overflow fails loudly instead of wrapping around`() {
        // Sin `addExact`, Long.MAX_VALUE + 1 daría un número NEGATIVO sin avisar. Acá debe fallar.
        assertThrows(ArithmeticException::class.java) { Money.of(Long.MAX_VALUE) + Money.of(1) }
        assertThrows(ArithmeticException::class.java) { Money.of(Long.MAX_VALUE) * 2 }
    }

    @Test
    fun `total of an empty list is zero and total of a list is the sum`() {
        assertEquals(Money.ZERO, emptyList<Money>().total())
        assertEquals(Money.of(600), listOf(Money.of(100), Money.of(200), Money.of(300)).total())
    }

    @Test
    fun `money compares by amount`() {
        assertTrue(Money.of(100) < Money.of(101))
        assertTrue(Money.of(100) >= Money.of(100))
        assertEquals(Money.of(50), minOf(Money.of(100), Money.of(50)))
    }

    @Test
    fun `isZero and isPositive`() {
        assertTrue(Money.ZERO.isZero)
        assertTrue(!Money.ZERO.isPositive)
        assertTrue(Money.of(1).isPositive)
    }

    @Test
    fun `percentBp rounds to the nearest cent with halves going up`() {
        // 10 % (1000 pb) de $10,05 = $1,005 → medio centavo → sube a $1,01.
        assertEquals(Money.of(101), Money.of(1005).percentBp(1000))
        // 10 % de $10,04 = $1,004 → baja a $1,00.
        assertEquals(Money.of(100), Money.of(1004).percentBp(1000))
        // 50 % de 1 centavo = 0,5 → sube a 1. 49,99 % de 1 centavo = 0,4999 → baja a 0.
        assertEquals(Money.of(1), Money.of(1).percentBp(5000))
        assertEquals(Money.ZERO, Money.of(1).percentBp(4999))
    }

    @Test
    fun `percentBp of zero percent and of one hundred percent`() {
        assertEquals(Money.ZERO, Money.of(12_345).percentBp(0))
        assertEquals(Money.of(12_345), Money.of(12_345).percentBp(10_000))
        assertEquals(Money.ZERO, Money.ZERO.percentBp(1500))
    }

    @Test
    fun `percentBp matches an independent BigDecimal calculation for many values`() {
        // Prueba contra un "oráculo": calculamos lo mismo con BigDecimal (que redondea HALF_UP de fábrica)
        // y comprobamos que nuestra aritmética entera da SIEMPRE el mismo resultado.
        val basisPoints = listOf(0, 1, 500, 1000, 1250, 1500, 3333, 5000, 9999, 10_000)
        for (minor in 0L..3000L) {
            for (bp in basisPoints) {
                val expected = BigDecimal(minor)
                    .multiply(BigDecimal(bp))
                    .divide(BigDecimal(10_000), 0, RoundingMode.HALF_UP)
                    .toLong()
                assertEquals(expected, Money.of(minor).percentBp(bp).minor, "minor=$minor bp=$bp")
            }
        }
    }

    @Test
    fun `percentBp rejects basis points outside the 0 to 10000 range`() {
        assertThrows(IllegalArgumentException::class.java) { Money.of(100).percentBp(-1) }
        assertThrows(IllegalArgumentException::class.java) { Money.of(100).percentBp(10_001) }
    }
}
