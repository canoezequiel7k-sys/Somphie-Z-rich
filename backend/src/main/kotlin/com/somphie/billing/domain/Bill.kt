package com.somphie.billing.domain

import com.somphie.orders.domain.ItemStatus
import com.somphie.orders.domain.OrderStatus
import com.somphie.shared.domain.Money
import com.somphie.shared.domain.total

/*
 * CUENTA DE LA SESIÓN (RN-BIL-01, RN-BIL-02, RN-TIP-02).
 *
 * Idea central: la cuenta NO se guarda, se DERIVA cada vez a partir de los pedidos, ajustes y pagos.
 * Así nunca queda desincronizada ("una sola fuente de verdad"). Y como es una función pura (recibe
 * datos, devuelve números), se puede probar sin base de datos.
 *
 * Nota de arquitectura: este archivo usa los enums de `orders` (OrderStatus, ItemStatus).
 * La dirección de dependencia billing → orders se confirmará con ArchUnit en el WP-BE-20.
 */

/**
 * Una línea de pedido vista desde la cuenta: solo lo que a billing le importa.
 * Se arma en la capa de aplicación a partir de los ítems reales.
 *
 * @property lineTotal `unitPriceMinor × quantity` ya calculado, con el PRECIO HISTÓRICO del snapshot
 *   (RN-BIL-01): nunca se consulta el catálogo actual, porque un cambio de precio posterior no debe
 *   alterar lo que el cliente ya pidió.
 */
data class ChargeLine(
    val orderStatus: OrderStatus,
    val itemStatus: ItemStatus,
    val lineTotal: Money,
) {
    /**
     * LA definición de "cargo facturable" (regla de oro 3): un ítem NO cancelado de un pedido en
     * `ACCEPTED`, `IN_PREPARATION`, `READY` o `DELIVERED`.
     *
     * Por eso un pedido pendiente, rechazado, cancelado o expirado NUNCA aparece en la cuenta (RN-ORD-19).
     */
    val isBillable: Boolean
        get() = orderStatus in BILLABLE_ORDER_STATUSES && itemStatus != ItemStatus.CANCELLED

    private companion object {
        val BILLABLE_ORDER_STATUSES = setOf(
            OrderStatus.ACCEPTED,
            OrderStatus.IN_PREPARATION,
            OrderStatus.READY,
            OrderStatus.DELIVERED,
        )
    }
}

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

/** Cargos facturables. Existe como objeto aparte para que haya UN solo lugar donde se suman. */
object BillableCharges {
    fun total(lines: Iterable<ChargeLine>): Money =
        lines.filter { it.isBillable }.map { it.lineTotal }.total()
}

/** Calcula el [BillSummary]. Función pura: mismos datos de entrada, mismo resultado, sin tocar nada. */
object BillCalculator {

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
