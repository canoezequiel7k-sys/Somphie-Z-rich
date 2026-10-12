package com.somphie.billing.domain

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.domain.Money
import java.time.Duration
import java.time.Instant

/*
 * REEMBOLSOS, ANULACIONES Y EFECTOS MONETARIOS DEL CIERRE FORZADO.
 * Funciones puras (sin base de datos ni reloj propio): el `now` llega por parámetro.
 */

object RefundRules {

    /**
     * RN-BIL-07: un reembolso no puede superar lo pagado NETO (pagos − reembolsos ya hechos).
     * La propina no entra: los reembolsos no la devuelven (RN-TIP-05).
     * Que el monto sea > 0 lo valida la capa API (`VALIDATION_FAILED`); acá es un error de programación.
     */
    fun validate(amount: Money, netPaid: Money) {
        require(amount.isPositive) { "Refund amount must be greater than zero" }
        if (amount > netPaid) {
            throw DomainException(
                ErrorCode.REFUND_EXCEEDS_PAID,
                "Refund exceeds the net paid amount",
                mapOf("maxRefundMinor" to netPaid.minor),
            )
        }
    }

    /**
     * D-159 / RN-SES-06: al forzar el cierre, si tras cancelar queda saldo NEGATIVO se registra, en la
     * misma transacción, un reembolso simulado automático por el exceso (sin propina). Devuelve su
     * importe, o `null` si no hace falta.
     */
    fun automaticRefundForForcedClose(balance: Balance): Money? =
        if (balance.isOverpaid) Money.of(-balance.minor) else null

    /**
     * D-159: si queda saldo POSITIVO, no se guarda nada: se calcula y se copia como "no cobrado" a la
     * auditoría y al comprobante (valor derivado, no guardado).
     */
    fun uncollectedAtForcedClose(balance: Balance): Money = balance.owed()
}

object VoidRules {

    /** RN-BIL-06: solo se anula un pago `SUCCEEDED` (el reembolso también es un `Payment`). */
    fun requirePaymentVoidable(status: PaymentStatus) {
        if (status != PaymentStatus.SUCCEEDED) {
            throw DomainException(
                ErrorCode.PAYMENT_NOT_VOIDABLE,
                "Only a succeeded payment can be voided",
                mapOf("currentStatus" to status),
            )
        }
    }

    /**
     * RN-ADJ-07: se anula un ajuste `APPLIED`. El Dueño ([canVoidAny]) anula cualquiera; el Mozo solo
     * los SUYOS ([isOwn], si no `FORBIDDEN`) y dentro de [window] (10 min, si no `ADJUSTMENT_NOT_VOIDABLE`).
     * Que la sesión esté `OPEN` y que haya motivo lo comprueba la capa de aplicación.
     */
    fun requireAdjustmentVoidable(
        status: AdjustmentStatus,
        appliedAt: Instant,
        now: Instant,
        canVoidAny: Boolean,
        isOwn: Boolean,
        window: Duration,
    ) {
        if (status != AdjustmentStatus.APPLIED) {
            throw DomainException(ErrorCode.ADJUSTMENT_NOT_VOIDABLE, "Adjustment is already voided")
        }
        if (canVoidAny) return
        if (!isOwn) {
            throw DomainException(ErrorCode.FORBIDDEN, "Staff can only void their own adjustments")
        }
        if (Duration.between(appliedAt, now) > window) {
            throw DomainException(ErrorCode.ADJUSTMENT_NOT_VOIDABLE, "The void window has expired")
        }
    }
}
