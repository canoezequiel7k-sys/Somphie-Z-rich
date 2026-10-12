package com.somphie.billing.domain

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.domain.Money

/**
 * Datos que hacen falta para validar un pago. Los arma la capa de aplicación DENTRO de la transacción,
 * con la sesión bloqueada (RN-BIL-02): validar contra un saldo "viejo" permitiría pagar de más.
 *
 * @property balance saldo actual de la cuenta.
 * @property grossCharges cargos BRUTOS (antes de ajustes): base del tope de propina (RN-TIP-03).
 * @property minPayment `minPaymentMinor` de los ajustes del restaurante (por defecto 100 = $1,00).
 * @property maxTipPercent `maxTipPercent` de los ajustes (por defecto 100): tope de cordura de la propina.
 */
data class PaymentContext(
    val balance: Balance,
    val grossCharges: Money,
    val minPayment: Money,
    val maxTipPercent: Int,
) {
    init {
        require(maxTipPercent >= 0) { "maxTipPercent cannot be negative (got $maxTipPercent)" }
    }

    /**
     * Propina máxima aceptada, en centavos: `charges × maxTipPercent / 100`, redondeada hacia abajo.
     *
     * Para enteros, `tip ≤ charges × pct / 100` es lo mismo que `tip ≤ floor(charges × pct / 100)`,
     * así que NO hay que elegir una regla de redondeo: el resultado es exacto.
     */
    fun maxTip(): Money = Money.of(Math.multiplyExact(grossCharges.minor, maxTipPercent.toLong()) / 100L)
}

/**
 * Reglas de un pago nuevo (RN-BIL-03, RN-TIP-01, RN-TIP-03, RN-TIP-04, D-146, D-158).
 *
 * Es una función PURA: valida y lanza [DomainException], o no hace nada. Nunca modifica datos, así que
 * un error de validación no puede dejar la cuenta a medias (regla de oro 12).
 */
object PaymentRules {

    /**
     * Valida un pago con monto principal [amount] y propina [tip] contra el contexto [context].
     *
     * Orden de las comprobaciones (cuando hay varios errores a la vez se informa el primero):
     * 1. Pago vacío (monto 0 y propina 0) → `TIP_INVALID`.
     * 2. Monto principal, solo si es mayor que 0.
     * 3. Propina, solo si es mayor que 0.
     * (El documento no dice qué error tiene prioridad si fallan monto y propina juntos; se eligió este
     * orden y queda anotado como hallazgo H-23.)
     */
    fun validate(amount: Money, tip: Money, context: PaymentContext) {
        if (amount.isZero && tip.isZero) {
            throw DomainException(ErrorCode.TIP_INVALID, "A payment needs an amount or a tip")
        }
        if (amount.isPositive) validateMainAmount(amount, context)
        if (tip.isPositive) validateTip(tip, context)
    }

    private fun validateMainAmount(amount: Money, context: PaymentContext) {
        val balance = context.balance

        // Nada que pagar: saldo cero o negativo. (Un pago "solo propina" no pasa por acá: D-146.)
        if (!balance.isOwed) {
            throw DomainException(
                ErrorCode.PAYMENT_NOTHING_TO_PAY,
                "The session has nothing left to pay",
            )
        }
        val owed = balance.owed()

        // D-158: el mínimo es `min(minPaymentMinor, saldo)`. Si al cliente le quedan $0,50 y el mínimo
        // es $1,00, tiene que poder pagar esos $0,50 (si no, la cuenta nunca podría saldarse).
        val minRequired = minOf(context.minPayment, owed)
        if (amount < minRequired) {
            throw DomainException(
                ErrorCode.PAYMENT_AMOUNT_INVALID,
                "Amount is below the minimum payment",
                mapOf("minMinor" to minRequired.minor), // ya calculado con el saldo, como pide el catálogo
            )
        }

        if (amount > owed) {
            throw DomainException(
                ErrorCode.PAYMENT_EXCEEDS_BALANCE,
                "Amount exceeds the current balance",
                mapOf("balanceMinor" to owed.minor),
            )
        }
    }

    private fun validateTip(tip: Money, context: PaymentContext) {
        // Sin cargos no se acepta propina: no hay nada que agradecer todavía.
        if (context.grossCharges.isZero) {
            throw DomainException(ErrorCode.TIP_NOT_ALLOWED, "A tip needs at least one charge in the session")
        }
        val maxTip = context.maxTip()
        if (tip > maxTip) {
            throw DomainException(
                ErrorCode.TIP_EXCEEDS_LIMIT,
                "Tip exceeds the allowed limit",
                mapOf("maxTipMinor" to maxTip.minor),
            )
        }
    }
}
