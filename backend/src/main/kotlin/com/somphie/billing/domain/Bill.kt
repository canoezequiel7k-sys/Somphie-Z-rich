package com.somphie.billing.domain

import com.somphie.shared.domain.Money
import com.somphie.shared.domain.total

/*
 * CUENTA DE LA SESIÓN (RN-BIL-01, RN-BIL-02, RN-TIP-02).
 *
 * Idea central: la cuenta NO se guarda, se DERIVA cada vez a partir de los pedidos, ajustes y pagos.
 * Así nunca queda desincronizada ("una sola fuente de verdad"). Y como es una función pura (recibe
 * datos, devuelve números), se puede probar sin base de datos.
 *
 * La aplicación obtiene cargos ya filtrados mediante el puerto `OrderChargesPort` de `orders` y
 * adapta sus importes a [ChargeLine]; así este dominio no importa tipos de otro módulo.
 */

/**
 * Importe de un ítem que `OrderChargesPort` ya calificó como facturable según RN-BIL-01.
 * La aplicación lo arma con el precio histórico del ítem y no vuelve a decidir su elegibilidad.
 *
 * @property lineTotal `unitPriceMinor × quantity` con el PRECIO HISTÓRICO del snapshot; un cambio
 * posterior del catálogo nunca altera lo que el cliente ya pidió.
 */
data class ChargeLine(
    val lineTotal: Money,
)

/** Un ajuste (descuento o cortesía) visto desde la cuenta. Solo los `APPLIED` restan. */
data class AdjustmentLine(
    val status: AdjustmentStatus,
    val amount: Money,
)

/**
 * Un pago o reembolso visto desde la cuenta.
 *
 * @property amount monto PRINCIPAL (el que reduce el saldo). Siempre positivo o cero, también en un
 *   reembolso: el signo lo da [kind].
 * @property tip propina (RN-TIP-02): se guarda aparte y NO reduce el saldo. Un pago "solo propina"
 *   tiene `amount = 0` y `tip > 0` (D-146).
 */
data class PaymentLine(
    val kind: PaymentKind,
    val status: PaymentStatus,
    val amount: Money,
    val tip: Money = Money.ZERO,
) {
    init {
        // RN-TIP-05: un reembolso NO devuelve propina. Si alguien intentara cargarle una, es un bug.
        require(kind != PaymentKind.REFUND || tip.isZero) { "A refund cannot carry a tip" }
    }
}

/**
 * Saldo de la cuenta. A diferencia de [Money], PUEDE ser negativo.
 *
 * - positivo: el cliente todavía debe ese importe (`isOwed`).
 * - cero: cuenta saldada (`isSettled`).
 * - negativo: se pagó de más, típicamente porque se canceló un ítem ya pagado (`isOverpaid`).
 *   Se resuelve con un reembolso (RN-BIL-07).
 */
@JvmInline
value class Balance(val minor: Long) {
    val isSettled: Boolean get() = minor == 0L
    val isOwed: Boolean get() = minor > 0L
    val isOverpaid: Boolean get() = minor < 0L

    /** Lo que se debe, como `Money`: el saldo si es positivo y cero en cualquier otro caso. */
    fun owed(): Money = if (minor > 0L) Money.of(minor) else Money.ZERO

    companion object {
        /**
         * RN-BIL-02: `balance = charges − adjustments − paid`.
         * Usa `subtractExact` (falla ruidosamente ante desbordamiento en vez de dar un número absurdo).
         * La propina NO participa (RN-TIP-02).
         */
        fun of(charges: Money, adjustments: Money, paid: Money): Balance =
            Balance(Math.subtractExact(Math.subtractExact(charges.minor, adjustments.minor), paid.minor))
    }
}

/**
 * Resumen de la cuenta de una sesión.
 *
 * @property charges cargos BRUTOS facturables (antes de ajustes). Es la base de los topes de propina
 *   (RN-TIP-03) y de descuento del Mozo (RN-ADJ-06).
 * @property adjustments suma de ajustes `APPLIED`.
 * @property paid pagado NETO: pagos `SUCCEEDED` menos reembolsos `SUCCEEDED`.
 * @property tips propinas de pagos `SUCCEEDED`; separadas de la cuenta (RN-TIP-07).
 */
data class BillSummary(
    val charges: Money,
    val adjustments: Money,
    val paid: Money,
    val tips: Money,
    val balance: Balance,
)

/** Suma importes que `OrderChargesPort` ya filtró conforme a RN-BIL-01. */
object BillableCharges {
    fun total(lines: Iterable<ChargeLine>): Money =
        lines.map { it.lineTotal }.total()
}

/** Calcula el [BillSummary]. Función pura: mismos datos de entrada, mismo resultado, sin tocar nada. */
object BillCalculator {

    /** Resume entidades de pago usando la misma regla de cálculo que las filas proyectadas desde persistencia. */
    fun summarizePayments(
        chargeLines: Iterable<ChargeLine>,
        adjustmentLines: Iterable<AdjustmentLine>,
        payments: Iterable<Payment>,
    ): BillSummary = summarize(
        chargeLines = chargeLines,
        adjustmentLines = adjustmentLines,
        paymentLines = payments.map { it.asBillLine() },
    )

    fun summarize(
        chargeLines: Iterable<ChargeLine>,
        adjustmentLines: Iterable<AdjustmentLine>,
        paymentLines: Iterable<PaymentLine>,
    ): BillSummary {
        val charges = BillableCharges.total(chargeLines)

        // Solo los ajustes APPLIED restan; los anulados (VOIDED) se ignoran.
        val adjustments = adjustmentLines
            .filter { it.status == AdjustmentStatus.APPLIED }
            .map { it.amount }
            .total()

        // Solo cuentan los pagos SUCCEEDED: los FAILED (rechazados) y VOIDED (anulados) no mueven el saldo.
        val succeeded = paymentLines.filter { it.status == PaymentStatus.SUCCEEDED }
        val payments = succeeded.filter { it.kind == PaymentKind.PAYMENT }.map { it.amount }.total()
        val refunds = succeeded.filter { it.kind == PaymentKind.REFUND }.map { it.amount }.total()

        // Invariante: no se puede haber reembolsado más de lo cobrado (REFUND_EXCEEDS_PAID lo impide antes).
        // Si esto fallara, los datos están corruptos: es un error del sistema, no del usuario.
        check(payments >= refunds) { "Refunds ($refunds) exceed payments ($payments): inconsistent data" }
        val paid = Money.of(payments.minor - refunds.minor)

        // La propina viene SOLO de pagos exitosos de tipo PAYMENT. Los reembolsos no la devuelven (RN-TIP-05)
        // y un pago anulado (VOIDED) ya quedó afuera del filtro `succeeded` (su propina se anula con él).
        val tips = succeeded.filter { it.kind == PaymentKind.PAYMENT }.map { it.tip }.total()

        return BillSummary(
            charges = charges,
            adjustments = adjustments,
            paid = paid,
            tips = tips,
            balance = Balance.of(charges, adjustments, paid),
        )
    }
}
