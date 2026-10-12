package com.somphie.billing.domain

import com.somphie.billing.domain.AdjustmentKind.COURTESY_ITEM
import com.somphie.billing.domain.AdjustmentKind.DISCOUNT_AMOUNT
import com.somphie.billing.domain.AdjustmentKind.DISCOUNT_PERCENT
import com.somphie.billing.domain.AdjustmentKind.MANUAL_ADJUSTMENT
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.domain.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Pruebas de los ajustes: RN-ADJ-03/04/05/06 y D-142. Importes en centavos. */
class AdjustmentRulesTest {

    // --- Ayudas ---

    /** Contexto por defecto: cargos 100.000, nada pagado, Mozo sin nada aplicado, límite 15 %. */
    private fun ctx(
        charges: Long = 100_000,
        adjustments: Long = 0,
        paid: Long = 0,
        staffSoFar: Long = 0,
        percent: Int = 15,
        absoluteCap: Long? = null,
        any: Boolean = false,
    ) = AdjustmentContext(
        grossCharges = Money.of(charges),
        appliedAdjustments = Money.of(adjustments),
        balance = Balance.of(Money.of(charges), Money.of(adjustments), Money.of(paid)),
        staffAppliedSoFar = Money.of(staffSoFar),
        maxStaffDiscountPercent = percent,
        maxStaffAbsolute = absoluteCap?.let { Money.of(it) },
        canApplyAny = any,
    )

    private fun percent(bp: Int) = AdjustmentRequest(DISCOUNT_PERCENT, percentBp = bp)
    private fun amount(cents: Long, kind: AdjustmentKind = DISCOUNT_AMOUNT) =
        AdjustmentRequest(kind, amount = Money.of(cents))

    private fun assertFails(code: ErrorCode, block: () -> Unit): DomainException {
        val error = assertThrows(DomainException::class.java) { block() }
        assertEquals(code, error.code)
        return error
    }

    // --- Cálculo del importe (RN-ADJ-03) ---

    @Test
    fun `a percent discount rounds to the nearest cent with halves going up`() {
        // 10 % de 10.005 = 1000,5 → sube a 1001. (`any` para aislar el cálculo del límite del Mozo.)
        assertEquals(Money.of(1001), AdjustmentRules.validate(percent(1000), ctx(charges = 10_005, any = true)))
    }

    @Test
    fun `a percent discount is calculated on charges minus the adjustments already applied`() {
        // Cargos 100.000 y ya hay 50.000 de ajustes: la base es 50.000, no 100.000.
        val result = AdjustmentRules.validate(percent(1000), ctx(adjustments = 50_000))
        assertEquals(Money.of(5_000), result)
    }

    @Test
    fun `amount manual and courtesy adjustments use the given amount`() {
        assertEquals(Money.of(700), AdjustmentRules.validate(amount(700), ctx()))
        assertEquals(Money.of(700), AdjustmentRules.validate(amount(700, MANUAL_ADJUSTMENT), ctx()))
        val courtesy = AdjustmentRequest(COURTESY_ITEM, itemLineTotal = Money.of(970))
        assertEquals(Money.of(970), AdjustmentRules.validate(courtesy, ctx()))
    }

    // --- Pedidos incoherentes → ADJUSTMENT_INVALID ---

    @Test
    fun `incoherent requests are rejected as invalid`() {
        val bad = listOf(
            percent(0),
            percent(10_001),
            AdjustmentRequest(DISCOUNT_PERCENT), // falta el porcentaje
            AdjustmentRequest(DISCOUNT_PERCENT, percentBp = 1000, amount = Money.of(5)), // sobra un dato
            AdjustmentRequest(DISCOUNT_AMOUNT), // falta el monto
            AdjustmentRequest(DISCOUNT_AMOUNT, percentBp = 1000, amount = Money.of(5)),
            amount(0), // el importe debe ser > 0
            AdjustmentRequest(COURTESY_ITEM), // falta el ítem
            AdjustmentRequest(COURTESY_ITEM, itemLineTotal = Money.of(5), amount = Money.of(5)),
        )
        bad.forEach { request ->
            assertFails(ErrorCode.ADJUSTMENT_INVALID) { AdjustmentRules.validate(request, ctx(any = true)) }
        }
    }

    @Test
    fun `a percentage that rounds to zero cents is invalid`() {
        // 0,01 % de 1 centavo = 0,0001 → redondea a 0.
        assertFails(ErrorCode.ADJUSTMENT_INVALID) {
            AdjustmentRules.validate(percent(1), ctx(charges = 1, any = true))
        }
    }

    // --- Nunca deja el saldo negativo (RN-ADJ-05) ---

    @Test
    fun `an adjustment cannot exceed the balance and the error reports the maximum`() {
        val context = ctx(paid = 95_000, any = true) // saldo 5.000
        assertEquals(Money.of(5_000), AdjustmentRules.validate(amount(5_000), context))
        val error = assertFails(ErrorCode.ADJUSTMENT_EXCEEDS_BALANCE) {
            AdjustmentRules.validate(amount(5_001), context)
        }
        assertEquals(5_000L, error.extra["maxAdjustmentMinor"])
    }

    @Test
    fun `with a zero or negative balance any adjustment exceeds the balance`() {
        assertFails(ErrorCode.ADJUSTMENT_EXCEEDS_BALANCE) {
            AdjustmentRules.validate(amount(1), ctx(paid = 100_000, any = true))
        }
        assertFails(ErrorCode.ADJUSTMENT_EXCEEDS_BALANCE) {
            AdjustmentRules.validate(amount(1), ctx(paid = 120_000, any = true))
        }
    }

    // --- Límite acumulado del Mozo (RN-ADJ-06, D-142) ---

    @Test
    fun `the staff limit is cumulative across the adjustments already applied`() {
        // Límite: 15 % de 100.000 = 15.000. Ya aplicó 10.000: quedan 5.000.
        val context = ctx(staffSoFar = 10_000)
        assertEquals(Money.of(5_000), AdjustmentRules.validate(amount(5_000), context))
        val error = assertFails(ErrorCode.ADJUSTMENT_LIMIT_EXCEEDED) {
            AdjustmentRules.validate(amount(5_001), context)
        }
        assertEquals(15, error.extra["limitPercent"])
        assertEquals(15_000L, error.extra["limitMinor"])
    }

    @Test
    fun `the owner with apply any has no limit`() {
        assertEquals(Money.of(100_000), AdjustmentRules.validate(amount(100_000), ctx(any = true)))
    }

    @Test
    fun `the optional absolute cap lowers the limit`() {
        val context = ctx(absoluteCap = 8_000) // 15 % serían 15.000, pero el tope absoluto es 8.000
        assertEquals(Money.of(8_000), AdjustmentRules.validate(amount(8_000), context))
        val error = assertFails(ErrorCode.ADJUSTMENT_LIMIT_EXCEEDED) {
            AdjustmentRules.validate(amount(8_001), context)
        }
        assertEquals(8_000L, error.extra["limitMinor"])
    }

    @Test
    fun `the percent limit is exact for integers`() {
        // 15 % de 1.005 = 150,75 → el tope entero es 150.
        val context = ctx(charges = 1_005)
        assertEquals(Money.of(150), AdjustmentRules.validate(amount(150), context))
        assertFails(ErrorCode.ADJUSTMENT_LIMIT_EXCEEDED) { AdjustmentRules.validate(amount(151), context) }
    }

    /** Oráculo: con cargos 1.000 y límite 15 %, un monto es válido si y solo si está entre 1 y 150. */
    @Test
    fun `limit validation matches an independent rule for many amounts`() {
        val context = ctx(charges = 1_000)
        for (cents in 0L..200L) {
            val shouldBeValid = cents in 1L..150L
            val result = runCatching { AdjustmentRules.validate(amount(cents), context) }
            assertEquals(shouldBeValid, result.isSuccess, "amount=$cents")
        }
    }

    @Test
    fun `when both the balance and the limit are exceeded the balance error is reported first`() {
        // Orden documentado en `AdjustmentRules.validate` (hallazgo H-24).
        val context = ctx(paid = 99_000) // saldo 1.000; límite del Mozo 15.000
        assertFails(ErrorCode.ADJUSTMENT_EXCEEDS_BALANCE) { AdjustmentRules.validate(amount(50_000), context) }
    }

    @Test
    fun `an out of range staff percentage is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) { ctx(percent = 101) }
        assertThrows(IllegalArgumentException::class.java) { ctx(percent = -1) }
    }
}
