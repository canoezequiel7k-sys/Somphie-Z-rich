package com.somphie.billing.domain

import com.somphie.billing.domain.PaymentKind.PAYMENT
import com.somphie.billing.domain.PaymentKind.REFUND
import com.somphie.billing.domain.PaymentStatus.FAILED
import com.somphie.billing.domain.PaymentStatus.SUCCEEDED
import com.somphie.billing.domain.PaymentStatus.VOIDED
import com.somphie.orders.domain.ItemStatus
import com.somphie.orders.domain.OrderStatus
import com.somphie.shared.domain.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Pruebas de la cuenta (RN-BIL-01, RN-BIL-02, RN-TIP-02, RN-TIP-05, RN-ORD-19).
 * Cada prueba dice en su nombre qué regla del negocio protege.
 */
class BillCalculatorTest {

    // --- Ayudas para armar datos de prueba sin repetir código ---
    private fun charge(order: OrderStatus, item: ItemStatus, cents: Long) =
        ChargeLine(order, item, Money.of(cents))

    private fun payment(cents: Long, tip: Long = 0, status: PaymentStatus = SUCCEEDED) =
        PaymentLine(PAYMENT, status, Money.of(cents), Money.of(tip))

    private fun refund(cents: Long, status: PaymentStatus = SUCCEEDED) =
        PaymentLine(REFUND, status, Money.of(cents))

    private fun adjustment(cents: Long, status: AdjustmentStatus = AdjustmentStatus.APPLIED) =
        AdjustmentLine(status, Money.of(cents))

    /** Línea facturable "normal": pedido entregado, ítem entregado. */
    private fun billable(cents: Long) = charge(OrderStatus.DELIVERED, ItemStatus.DELIVERED, cents)

    private fun summarize(
        charges: List<ChargeLine> = emptyList(),
        adjustments: List<AdjustmentLine> = emptyList(),
        payments: List<PaymentLine> = emptyList(),
    ) = BillCalculator.summarize(charges, adjustments, payments)

    // --- Qué cuenta como cargo (RN-BIL-01 y RN-ORD-19) ---

    /**
     * Recorre TODAS las combinaciones (estado de pedido × estado de ítem). La lista de estados que
     * cobran está escrita a mano acá, separada del código, para detectar si alguien la cambia sin querer.
     */
    @TestFactory
    fun `a line is billable only in accepted to delivered orders and when the item is not cancelled`():
        List<DynamicTest> {
        val billableOrders = setOf(
            OrderStatus.ACCEPTED, OrderStatus.IN_PREPARATION, OrderStatus.READY, OrderStatus.DELIVERED,
        )
        return OrderStatus.entries.flatMap { order ->
            ItemStatus.entries.map { item ->
                dynamicTest("order $order with item $item") {
                    val expected = if (order in billableOrders && item != ItemStatus.CANCELLED) 1_000L else 0L
                    assertEquals(Money.of(expected), BillableCharges.total(listOf(charge(order, item, 1_000))))
                }
            }
        }
    }

    @Test
    fun `charges sum only the billable lines`() {
        val charges = listOf(
            billable(970_000),
            charge(OrderStatus.PENDING_REVIEW, ItemStatus.PENDING, 300_000), // pendiente: no cobra
            charge(OrderStatus.REJECTED, ItemStatus.CANCELLED, 500_000), // rechazado: no cobra
            charge(OrderStatus.ACCEPTED, ItemStatus.CANCELLED, 100_000), // ítem excluido/cancelado: no cobra
            charge(OrderStatus.ACCEPTED, ItemStatus.QUEUED, 480_000),
        )
        assertEquals(Money.of(1_450_000), summarize(charges = charges).charges)
    }

    // --- Ajustes y pagos ---

    @Test
    fun `voided adjustments do not subtract`() {
        val summary = summarize(
            charges = listOf(billable(1_000_000)),
            adjustments = listOf(adjustment(100_000), adjustment(500_000, AdjustmentStatus.VOIDED)),
        )
        assertEquals(Money.of(100_000), summary.adjustments)
        assertEquals(Balance(900_000), summary.balance)
    }

    @Test
    fun `failed and voided payments do not count`() {
        val summary = summarize(
            charges = listOf(billable(1_000_000)),
            payments = listOf(
                payment(400_000),
                payment(300_000, status = FAILED), // rechazado en la simulación
                payment(200_000, status = VOIDED), // anulado por el personal
            ),
        )
        assertEquals(Money.of(400_000), summary.paid)
        assertEquals(Balance(600_000), summary.balance)
    }

    @Test
    fun `a refund reduces the net paid amount`() {
        val summary = summarize(
            charges = listOf(billable(1_000_000)),
            payments = listOf(payment(1_000_000), refund(250_000)),
        )
        assertEquals(Money.of(750_000), summary.paid)
        assertEquals(Balance(250_000), summary.balance)
    }

    @Test
    fun `a failed or voided refund does not count`() {
        val summary = summarize(
            charges = listOf(billable(1_000_000)),
            payments = listOf(payment(1_000_000), refund(250_000, FAILED), refund(100_000, VOIDED)),
        )
        assertEquals(Money.of(1_000_000), summary.paid)
        assertTrue(summary.balance.isSettled)
    }

    // --- Saldo (RN-BIL-02) ---

    @Test
    fun `balance matches the example in the API contract`() {
        // Mismo ejemplo numérico que `GET /sessions/current/bill` en openapi.yaml.
        val summary = summarize(
            charges = listOf(billable(1_450_000)),
            adjustments = listOf(adjustment(145_000)),
            payments = listOf(payment(500_000, tip = 50_000)),
        )
        assertEquals(Money.of(1_450_000), summary.charges)
        assertEquals(Money.of(145_000), summary.adjustments)
        assertEquals(Money.of(500_000), summary.paid)
        assertEquals(Money.of(50_000), summary.tips)
        assertEquals(Balance(805_000), summary.balance)
        assertTrue(summary.balance.isOwed)
    }

    @Test
    fun `an empty session has a settled balance of zero`() {
        val summary = summarize()
        assertTrue(summary.balance.isSettled)
        assertEquals(Money.ZERO, summary.charges)
    }

    @Test
    fun `cancelling an already paid item leaves a negative balance`() {
        // Se pagó todo (1.000.000) y después se cancela un ítem de 400.000: quedan 600.000 de cargos.
        val summary = summarize(
            charges = listOf(billable(600_000), charge(OrderStatus.ACCEPTED, ItemStatus.CANCELLED, 400_000)),
            payments = listOf(payment(1_000_000)),
        )
        assertEquals(Balance(-400_000), summary.balance)
        assertTrue(summary.balance.isOverpaid)
        assertEquals(Money.ZERO, summary.balance.owed()) // no se "debe" nada: hay que reembolsar
    }

    // --- Propina (RN-TIP-02, RN-TIP-05) ---

    @Test
    fun `a tip never changes the balance`() {
        val withoutTip = summarize(charges = listOf(billable(1_000_000)), payments = listOf(payment(400_000)))
        val withTip = summarize(
            charges = listOf(billable(1_000_000)),
            payments = listOf(payment(400_000, tip = 80_000)),
        )
        assertEquals(withoutTip.balance, withTip.balance)
        assertEquals(Money.of(80_000), withTip.tips)
    }

    @Test
    fun `a tip only payment with amount zero adds tip and leaves the balance alone`() {
        val summary = summarize(
            charges = listOf(billable(1_000_000)),
            payments = listOf(payment(1_000_000), payment(0, tip = 100_000)),
        )
        assertTrue(summary.balance.isSettled)
        assertEquals(Money.of(100_000), summary.tips)
    }

    @Test
    fun `voiding a payment also voids its tip`() {
        val summary = summarize(
            charges = listOf(billable(1_000_000)),
            payments = listOf(payment(400_000, tip = 50_000, status = VOIDED)),
        )
        assertEquals(Money.ZERO, summary.tips)
    }

    @Test
    fun `a refund does not give the tip back`() {
        val summary = summarize(
            charges = listOf(billable(600_000)),
            payments = listOf(payment(1_000_000, tip = 50_000), refund(400_000)),
        )
        assertEquals(Money.of(50_000), summary.tips)
    }

    @Test
    fun `a refund cannot carry a tip`() {
        assertThrows(IllegalArgumentException::class.java) {
            PaymentLine(REFUND, SUCCEEDED, Money.of(100), Money.of(10))
        }
    }

    // --- Datos corruptos ---

    @Test
    fun `refunds greater than payments signal inconsistent data`() {
        // Esto no debería pasar nunca (REFUND_EXCEEDS_PAID lo impide antes). Si pasa, hay datos corruptos
        // y el cálculo debe fallar en lugar de devolver un número engañoso.
        assertThrows(IllegalStateException::class.java) {
            summarize(payments = listOf(payment(100_000), refund(200_000)))
        }
    }
}
