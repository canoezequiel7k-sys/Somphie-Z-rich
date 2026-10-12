package com.somphie.billing.domain

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.domain.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Pruebas de las invariantes propias, transiciones y proyección contable de [Payment]. */
class PaymentTest {

    /** Construye un cobro de prueba con importes y sesión controlados por cada caso. */
    private fun payment(
        amountMinor: Long = 500,
        tipMinor: Long = 0,
        status: PaymentStatus = PaymentStatus.SUCCEEDED,
    ): Payment = Payment.record(
        id = UUID.randomUUID(),
        sessionId = UUID.randomUUID(),
        method = PaymentMethod.SIMULATED_ONLINE,
        status = status,
        amount = Money.of(amountMinor),
        tip = Money.of(tipMinor),
        idempotencyKey = UUID.randomUUID().toString(),
    )

    /** Permite el pago solo propina documentado y no incorpora la propina al saldo. */
    @Test
    fun `records a tip-only payment and projects the tip separately`() {
        val payment = payment(amountMinor = 0, tipMinor = 100)

        assertEquals(Money.ZERO, payment.asBillLine().amount)
        assertEquals(Money.of(100), payment.asBillLine().tip)
    }

    /** Integra la entidad con la cuenta y demuestra que principal y propina se contabilizan por separado. */
    @Test
    fun `bill calculator totals payment principal without adding its tip`() {
        val summary = BillCalculator.summarizePayments(
            chargeLines = listOf(
                ChargeLine(lineTotal = Money.of(500)),
            ),
            adjustmentLines = emptyList(),
            payments = listOf(payment(amountMinor = 500, tipMinor = 100)),
        )

        assertEquals(Money.of(500), summary.paid)
        assertEquals(Money.of(100), summary.tips)
        assertTrue(summary.balance.isSettled)
    }

    /** Impide registrar importes vacíos o propinas en cobros que fallaron. */
    @Test
    fun `rejects empty payments and tips on failed payments`() {
        assertThrows(IllegalArgumentException::class.java) {
            payment(amountMinor = 0, tipMinor = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            payment(amountMinor = 500, tipMinor = 100, status = PaymentStatus.FAILED)
        }
    }

    /** Un reembolso respeta el máximo neto y nunca contiene propina. */
    @Test
    fun `creates a refund only within the net paid amount`() {
        val refund = Payment.refund(
            id = UUID.randomUUID(),
            sessionId = UUID.randomUUID(),
            method = PaymentMethod.CASH_COUNTER,
            amount = Money.of(300),
            netPaid = Money.of(500),
            reason = "Ajuste por cierre forzado",
            idempotencyKey = UUID.randomUUID().toString(),
        )

        assertEquals(PaymentKind.REFUND, refund.kind)
        assertEquals(Money.ZERO, refund.tip)
        assertEquals(
            "Ajuste por cierre forzado",
            refund.void("Corrección del reembolso", Instant.parse("2026-01-01T12:00:00Z")).reason,
        )
        val error = assertThrows(DomainException::class.java) {
            Payment.refund(
                id = UUID.randomUUID(),
                sessionId = UUID.randomUUID(),
                method = PaymentMethod.CASH_COUNTER,
                amount = Money.of(501),
                netPaid = Money.of(500),
                reason = "Devolución",
                idempotencyKey = UUID.randomUUID().toString(),
            )
        }
        assertEquals(ErrorCode.REFUND_EXCEEDS_PAID, error.code)
    }

    /** La anulación crea una copia terminal y conserva inmutable el cobro original. */
    @Test
    fun `voids a succeeded payment without mutating the original`() {
        val original = payment(tipMinor = 50)
        val now = Instant.parse("2026-01-01T12:00:00Z")

        val voided = original.void("Cobro duplicado", now)

        assertEquals(PaymentStatus.SUCCEEDED, original.status)
        assertEquals(Money.of(50), original.tip)
        assertEquals(PaymentStatus.VOIDED, voided.status)
        assertEquals(now, voided.voidedAt)
    }

    /** Informa el error catalogado y deja intactos los movimientos que no pueden anularse. */
    @Test
    fun `does not void failed or already voided payments`() {
        val failed = payment(status = PaymentStatus.FAILED)
        val error = assertThrows(DomainException::class.java) {
            failed.void("Intento inválido", Instant.parse("2026-01-01T12:00:00Z"))
        }
        assertEquals(ErrorCode.PAYMENT_NOT_VOIDABLE, error.code)
        assertEquals(PaymentStatus.FAILED, failed.status)

        val voided = payment().void("Anulación válida", Instant.parse("2026-01-01T12:00:00Z"))
        assertThrows(DomainException::class.java) {
            voided.void("Segunda anulación", Instant.parse("2026-01-01T12:01:00Z"))
        }
        assertTrue(voided.voidedAt != null)
    }
}
