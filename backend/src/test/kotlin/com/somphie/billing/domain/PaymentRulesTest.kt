package com.somphie.billing.domain

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.domain.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Pruebas de las reglas de un pago nuevo: RN-BIL-03, RN-TIP-01, RN-TIP-03, RN-TIP-04, D-146 y D-158.
 * Los números de los ejemplos están en centavos (100 = $1,00).
 */
class PaymentRulesTest {

    // --- Ayudas ---

    /** Contexto con valores por defecto: mínimo $1,00 (100) y tope de propina 100 %. */
    private fun context(
        balance: Long,
        charges: Long = 100_000,
        minPayment: Long = 100,
        maxTipPercent: Int = 100,
    ) = PaymentContext(Balance(balance), Money.of(charges), Money.of(minPayment), maxTipPercent)

    private fun validate(amount: Long, tip: Long = 0, context: PaymentContext) =
        PaymentRules.validate(Money.of(amount), Money.of(tip), context)

    /**
     * Comprueba que la validación ACEPTA el pago. Si lanzara una excepción, el test fallaría solo; esta
     * función existe para que el nombre diga la intención ("esto debe ser válido") y para evitar la
     * ambigüedad de `Assertions.assertDoesNotThrow` entre sus dos versiones al usar lambdas en Kotlin.
     */
    private fun assertDoesNotThrow(block: () -> Unit) = block()

    /** Verifica que falle con [code] y devuelve la excepción para mirar sus datos extra. */
    private fun assertFails(code: ErrorCode, block: () -> Unit): DomainException {
        val error = assertThrows(DomainException::class.java) { block() }
        assertEquals(code, error.code)
        return error
    }

    // --- Pago vacío ---

    @Test
    fun `a payment with no amount and no tip is invalid`() {
        assertFails(ErrorCode.TIP_INVALID) { validate(0, 0, context(balance = 5_000)) }
    }

    // --- Monto principal (RN-BIL-03) ---

    @Test
    fun `nothing to pay when the balance is zero or negative`() {
        assertFails(ErrorCode.PAYMENT_NOTHING_TO_PAY) { validate(500, context = context(balance = 0)) }
        assertFails(ErrorCode.PAYMENT_NOTHING_TO_PAY) { validate(500, context = context(balance = -2_000)) }
    }

    @Test
    fun `the amount must reach the minimum payment`() {
        val ctx = context(balance = 10_000, minPayment = 100)
        assertFails(ErrorCode.PAYMENT_AMOUNT_INVALID) { validate(99, context = ctx) }
        assertDoesNotThrow { validate(100, context = ctx) } // justo el mínimo: válido
    }

    @Test
    fun `the minimum is capped by the balance as D-158 says`() {
        // Quedan $0,50 de saldo y el mínimo es $1,00: el cliente debe poder pagar esos $0,50 para saldar.
        val ctx = context(balance = 50, minPayment = 100)
        assertDoesNotThrow { validate(50, context = ctx) }

        // El error informa el mínimo YA calculado con el saldo (50), no el configurado (100).
        val error = assertFails(ErrorCode.PAYMENT_AMOUNT_INVALID) { validate(49, context = ctx) }
        assertEquals(50L, error.extra["minMinor"])
    }

    @Test
    fun `the amount cannot exceed the balance and the error reports the current balance`() {
        val ctx = context(balance = 10_000)
        assertDoesNotThrow { validate(10_000, context = ctx) } // pagar todo: válido
        val error = assertFails(ErrorCode.PAYMENT_EXCEEDS_BALANCE) { validate(10_001, context = ctx) }
        assertEquals(10_000L, error.extra["balanceMinor"])
    }

    /**
     * Prueba EXHAUSTIVA contra un "oráculo": una regla escrita aparte, en una sola línea, que dice cuándo
     * un monto debe ser válido. Se compara con la validación real para miles de combinaciones de saldo y
     * monto. Si el código y la regla alguna vez discrepan, esta prueba muestra cuál combinación falló.
     */
    @Test
    fun `main amount validation matches an independent rule for many balances and amounts`() {
        val minPayment = 100L
        for (balance in -5L..250L) {
            val ctx = context(balance = balance, minPayment = minPayment)
            for (amount in 1L..260L) {
                // Regla independiente: hay saldo, y min(mínimo, saldo) ≤ monto ≤ saldo.
                val shouldBeValid = balance > 0 && amount >= minOf(minPayment, balance) && amount <= balance
                val result = runCatching { validate(amount, context = ctx) }
                assertEquals(shouldBeValid, result.isSuccess, "balance=$balance amount=$amount")
            }
        }
    }

    // --- Pago solo propina (D-146, RN-TIP-04) ---

    @Test
    fun `a tip only payment is allowed even when the balance is zero`() {
        // La cuenta está saldada (saldo 0) pero hubo cargos: el cliente igual puede dejar propina.
        assertDoesNotThrow { validate(0, tip = 5_000, context = context(balance = 0, charges = 100_000)) }
    }

    @Test
    fun `a tip only payment is allowed even when the balance is negative`() {
        assertDoesNotThrow { validate(0, tip = 5_000, context = context(balance = -1_000, charges = 100_000)) }
    }

    // --- Propina (RN-TIP-03) ---

    @Test
    fun `a tip is not allowed when the session has no charges`() {
        assertFails(ErrorCode.TIP_NOT_ALLOWED) { validate(0, tip = 100, context = context(balance = 0, charges = 0)) }
    }

    @Test
    fun `the tip cannot exceed the percentage of gross charges and the error reports the maximum`() {
        val ctx = context(balance = 0, charges = 10_000, maxTipPercent = 100)
        assertDoesNotThrow { validate(0, tip = 10_000, context = ctx) } // justo el tope (100 %)
        val error = assertFails(ErrorCode.TIP_EXCEEDS_LIMIT) { validate(0, tip = 10_001, context = ctx) }
        assertEquals(10_000L, error.extra["maxTipMinor"])
    }

    @Test
    fun `the tip limit is exact for integers without choosing a rounding rule`() {
        // 15 % de 1.005 = 150,75 → el tope entero es 150: una propina de 150 pasa y una de 151 no.
        val ctx = context(balance = 0, charges = 1_005, maxTipPercent = 15)
        assertDoesNotThrow { validate(0, tip = 150, context = ctx) }
        val error = assertFails(ErrorCode.TIP_EXCEEDS_LIMIT) { validate(0, tip = 151, context = ctx) }
        assertEquals(150L, error.extra["maxTipMinor"])
    }

    @Test
    fun `the tip limit uses gross charges and not the balance`() {
        // Saldo 0 (todo pagado) pero cargos brutos de 10.000: el tope de propina sigue siendo sobre 10.000.
        val ctx = context(balance = 0, charges = 10_000, maxTipPercent = 20)
        assertDoesNotThrow { validate(0, tip = 2_000, context = ctx) }
    }

    @Test
    fun `a payment can carry an amount and a tip together`() {
        val ctx = context(balance = 10_000, charges = 10_000)
        assertDoesNotThrow { validate(5_000, tip = 500, context = ctx) }
    }

    // --- Cuando hay varios errores a la vez ---

    @Test
    fun `when both the amount and the tip are wrong the amount error is reported first`() {
        // Orden documentado en PaymentRules.validate (hallazgo H-23).
        val ctx = context(balance = 10_000, charges = 10_000, maxTipPercent = 10)
        assertFails(ErrorCode.PAYMENT_EXCEEDS_BALANCE) { validate(20_000, tip = 999_999, context = ctx) }
    }

    @Test
    fun `a negative tip percent is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) { context(balance = 0, maxTipPercent = -1) }
    }
}
