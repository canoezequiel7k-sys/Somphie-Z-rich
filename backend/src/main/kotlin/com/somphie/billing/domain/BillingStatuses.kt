package com.somphie.billing.domain

/**
 * Tipo de movimiento de dinero (`estados-y-transiciones.md` §5).
 * `PAYMENT` = el cliente paga; `REFUND` = el personal devuelve dinero (se usa cuando el saldo queda negativo).
 */
enum class PaymentKind { PAYMENT, REFUND }

/**
 * Estado de un pago (§5):
 * - `SUCCEEDED`: aprobado, cuenta para el saldo.
 * - `FAILED`: rechazado en la simulación (modo demo); se registra pero NO cuenta.
 * - `VOIDED`: anulado por el personal; deja de contar (y su propina también, RN-TIP-05).
 */
enum class PaymentStatus { SUCCEEDED, FAILED, VOIDED }

/**
 * Estado de un ajuste de cuenta, es decir un descuento o cortesía (§8.2).
 * `APPLIED` resta de la cuenta; `VOIDED` fue anulado y deja de restar.
 */
enum class AdjustmentStatus { APPLIED, VOIDED }
