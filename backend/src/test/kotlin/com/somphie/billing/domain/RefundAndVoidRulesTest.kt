package com.somphie.billing.domain

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.domain.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** Pruebas de reembolsos, anulaciones y efectos del cierre forzado: RN-BIL-06/07, RN-ADJ-07, D-159. */
class RefundAndVoidRulesTest {

    private val applied = Instant.parse("2026-10-12T10:00:00Z")
    private val window = Duration.ofMinutes(10)

    private fun assertFails(code: ErrorCode, block: () -> Unit): DomainException {
        val error = assertThrows(DomainException::class.java) { block() }
        assertEquals(code, error.code)
        return error
    }

    // --- Reembolso (RN-BIL-07) ---

    @Test
    fun `a refund up to the net paid amount is valid and above it fails with the maximum`() {
        RefundRules.validate(Money.of(500), Money.of(500)) // justo lo pagado: válido
        val error = assertFails(ErrorCode.REFUND_EXCEEDS_PAID) {
            RefundRules.validate(Money.of(501), Money.of(500))
        }
        assertEquals(500L, error.extra["maxRefundMinor"])
    }

    @Test
    fun `a zero refund is a programming error because the API layer validates it first`() {
        assertThrows(IllegalArgumentException::class.java) { RefundRules.validate(Money.ZERO, Money.of(500)) }
    }

    // --- Cierre forzado (D-159) ---

    @Test
    fun `forced close refunds the excess when the balance is negative`() {
        assertEquals(Money.of(400), RefundRules.automaticRefundForForcedClose(Balance(-400)))
        assertEquals(Money.ZERO, RefundRules.uncollectedAtForcedClose(Balance(-400)))
    }

    @Test
    fun `forced close with a settled balance needs no refund and leaves nothing uncollected`() {
        assertNull(RefundRules.automaticRefundForForcedClose(Balance(0)))
        assertEquals(Money.ZERO, RefundRules.uncollectedAtForcedClose(Balance(0)))
    }

    @Test
    fun `forced close with a positive balance records it as uncollected without a refund`() {
        assertNull(RefundRules.automaticRefundForForcedClose(Balance(300)))
        assertEquals(Money.of(300), RefundRules.uncollectedAtForcedClose(Balance(300)))
    }

    // --- Anular un pago (RN-BIL-06) ---

    @Test
    fun `only a succeeded payment can be voided`() {
        VoidRules.requirePaymentVoidable(PaymentStatus.SUCCEEDED)
        listOf(PaymentStatus.FAILED, PaymentStatus.VOIDED).forEach { status ->
            val error = assertFails(ErrorCode.PAYMENT_NOT_VOIDABLE) { VoidRules.requirePaymentVoidable(status) }
            assertEquals(status, error.extra["currentStatus"])
        }
    }

    // --- Anular un ajuste (RN-ADJ-07) ---

    private fun voidAdjustment(
        status: AdjustmentStatus = AdjustmentStatus.APPLIED,
        now: Instant = applied.plusSeconds(60),
        any: Boolean = false,
        own: Boolean = true,
    ) = VoidRules.requireAdjustmentVoidable(status, applied, now, canVoidAny = any, isOwn = own, window = window)

    @Test
    fun `an already voided adjustment cannot be voided again`() {
        assertFails(ErrorCode.ADJUSTMENT_NOT_VOIDABLE) { voidAdjustment(status = AdjustmentStatus.VOIDED, any = true) }
    }

    @Test
    fun `the owner can void any adjustment at any time`() {
        voidAdjustment(any = true, own = false, now = applied.plus(Duration.ofDays(1)))
    }

    @Test
    fun `a waiter can void their own adjustment inside the window including its exact limit`() {
        voidAdjustment(now = applied.plus(window))
    }

    @Test
    fun `a waiter cannot void their own adjustment one second after the window`() {
        assertFails(ErrorCode.ADJUSTMENT_NOT_VOIDABLE) {
            voidAdjustment(now = applied.plus(window).plusSeconds(1))
        }
    }

    @Test
    fun `a waiter cannot void someone else's adjustment`() {
        assertFails(ErrorCode.FORBIDDEN) { voidAdjustment(own = false) }
    }
}
