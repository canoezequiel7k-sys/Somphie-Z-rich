package com.somphie.billing.domain

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.domain.Money
import java.time.Instant
import java.util.UUID

/**
 * Movimiento de pago inmutable asociado a una sesión (modelo-dominio.md y estados-y-transiciones.md §5).
 *
 * El cálculo contextual del pago se delega a [PaymentRules], y el límite de un reembolso a
 * [RefundRules]; esta entidad conserva solo invariantes propios y transiciones de estado.
 */
class Payment private constructor(
    /** Identificador estable del movimiento para consultas y auditoría. */
    val id: UUID,
    /** Sesión cuyo saldo afecta el movimiento. */
    val sessionId: UUID,
    /** Distingue el cobro de un reembolso cuyo signo se aplica al calcular la cuenta. */
    val kind: PaymentKind,
    /** Medio empleado para registrar el movimiento. */
    val method: PaymentMethod,
    /** Estado que determina si el movimiento afecta el saldo o la propina. */
    val status: PaymentStatus,
    /** Importe principal en centavos; el signo contable depende de [kind]. */
    val amount: Money,
    /** Propina separada del saldo y permitida solo en movimientos de cobro. */
    val tip: Money,
    /** Participante que pagó, si el movimiento se originó desde una sesión de cliente. */
    val payerParticipantId: UUID?,
    /** Etiqueta opcional para identificar un pago en efectivo registrado por personal. */
    val payerLabel: String?,
    /** Motivo persistido del reembolso; el motivo de anulación vive solo en el evento de auditoría. */
    val reason: String?,
    /** Clave que permite a la aplicación deduplicar reintentos de la misma operación. */
    val idempotencyKey: String,
    /** Instante de anulación; solo existe cuando el estado es `VOIDED`. */
    val voidedAt: Instant?,
) {
    init {
        // Una anulación siempre requiere instante y los demás estados nunca deben conservar uno.
        require(status != PaymentStatus.VOIDED || voidedAt != null) {
            "A voided payment must have a voidedAt timestamp"
        }
        require(status == PaymentStatus.VOIDED || voidedAt == null) {
            "A non-voided payment cannot have a voidedAt timestamp"
        }
        // Los reembolsos devuelven principal y nunca acreditan propina.
        require(kind != PaymentKind.REFUND || tip.isZero) {
            "A refund cannot carry a tip"
        }
        // El principal del reembolso debe ser mayor que cero.
        require(kind != PaymentKind.REFUND || amount.isPositive) {
            "A refund amount must be greater than zero"
        }
        // Un cobro vacío solo es válido cuando registra una propina positiva por separado.
        require(kind != PaymentKind.PAYMENT || amount.isPositive || tip.isPositive) {
            "A payment needs a principal amount or a tip"
        }
        // Un cobro fallido no acredita propina porque el pago no llegó a concretarse.
        require(status != PaymentStatus.FAILED || tip.isZero) {
            "A failed payment cannot record a tip"
        }
        // El reembolso conserva su causa como dato; el motivo de anulación se audita externamente.
        require(kind != PaymentKind.REFUND || !reason.isNullOrBlank()) {
            "A refund must include a reason"
        }
    }

    /**
     * Anula únicamente un movimiento exitoso y devuelve una copia con la propina excluida del cálculo.
     *
     * [reason] se valida, pero no se persiste en este objeto; la aplicación debe guardarlo en auditoría.
     * No reemplaza el motivo original de un reembolso. [VoidRules] conserva el código de error compartido;
     * el original permanece intacto si la transición no está permitida.
     */
    fun void(reason: String, now: Instant): Payment {
        VoidRules.requirePaymentVoidable(status)
        require(reason.isNotBlank()) { "A payment void reason must not be blank" }
        return Payment(
            id = id,
            sessionId = sessionId,
            kind = kind,
            method = method,
            status = PaymentStatus.VOIDED,
            amount = amount,
            tip = tip,
            payerParticipantId = payerParticipantId,
            payerLabel = payerLabel,
            reason = this.reason,
            idempotencyKey = idempotencyKey,
            voidedAt = now,
        )
    }

    /** Proyecta el movimiento al modelo mínimo que consume el cálculo derivado de la cuenta. */
    fun asBillLine(): PaymentLine = PaymentLine(
        kind = kind,
        status = status,
        amount = amount,
        tip = tip,
    )

    companion object {
        /** Reconstruye un movimiento ya persistido, preservando sus invariantes de dominio. */
        fun restore(
            id: UUID,
            sessionId: UUID,
            kind: PaymentKind,
            method: PaymentMethod,
            status: PaymentStatus,
            amount: Money,
            tip: Money,
            payerParticipantId: UUID?,
            payerLabel: String?,
            reason: String?,
            idempotencyKey: String,
            voidedAt: Instant?,
        ): Payment = Payment(
            id = id,
            sessionId = sessionId,
            kind = kind,
            method = method,
            status = status,
            amount = amount,
            tip = tip,
            payerParticipantId = payerParticipantId,
            payerLabel = payerLabel,
            reason = reason,
            idempotencyKey = idempotencyKey,
            voidedAt = voidedAt,
        )

        /**
         * Registra un cobro ya validado por [PaymentRules] con el resultado del proveedor simulado.
         *
         * Los fallos no guardan propina (RN-TIP-09), y el identificador idempotente queda asociado al
         * movimiento para que la capa de aplicación pueda imponer unicidad por sesión.
         */
        fun record(
            id: UUID,
            sessionId: UUID,
            method: PaymentMethod,
            status: PaymentStatus,
            amount: Money,
            tip: Money = Money.ZERO,
            payerParticipantId: UUID? = null,
            payerLabel: String? = null,
            idempotencyKey: String,
        ): Payment {
            require(status != PaymentStatus.VOIDED) {
                "A new payment cannot start in the VOIDED state"
            }
            return Payment(
                id = id,
                sessionId = sessionId,
                kind = PaymentKind.PAYMENT,
                method = method,
                status = status,
                amount = amount,
                tip = tip,
                payerParticipantId = payerParticipantId,
                payerLabel = payerLabel,
                reason = null,
                idempotencyKey = idempotencyKey,
                voidedAt = null,
            )
        }

        /**
         * Crea un reembolso exitoso después de validar su importe contra el neto ya pagado.
         *
         * El motivo es obligatorio; el importe y el límite usan [RefundRules] para evitar duplicar
         * la regla de negocio en la entidad.
         */
        fun refund(
            id: UUID,
            sessionId: UUID,
            method: PaymentMethod,
            amount: Money,
            netPaid: Money,
            reason: String,
            idempotencyKey: String,
        ): Payment {
            require(reason.isNotBlank()) { "A refund reason must not be blank" }
            RefundRules.validate(amount, netPaid)
            return Payment(
                id = id,
                sessionId = sessionId,
                kind = PaymentKind.REFUND,
                method = method,
                status = PaymentStatus.SUCCEEDED,
                amount = amount,
                tip = Money.ZERO,
                payerParticipantId = null,
                payerLabel = null,
                reason = reason,
                idempotencyKey = idempotencyKey,
                voidedAt = null,
            )
        }
    }
}
