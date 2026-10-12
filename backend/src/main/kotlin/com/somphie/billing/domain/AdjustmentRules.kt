package com.somphie.billing.domain

import com.somphie.billing.domain.AdjustmentKind.COURTESY_ITEM
import com.somphie.billing.domain.AdjustmentKind.DISCOUNT_AMOUNT
import com.somphie.billing.domain.AdjustmentKind.DISCOUNT_PERCENT
import com.somphie.billing.domain.AdjustmentKind.MANUAL_ADJUSTMENT
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.domain.Money

/*
 * AJUSTES DE CUENTA: descuentos, cortesías y ajustes manuales (RN-ADJ-01…06, D-142).
 *
 * Igual que el resto de `billing/domain`, son funciones PURAS: reciben datos ya cargados (la capa de
 * aplicación los arma DENTRO de la transacción, con la sesión bloqueada `FOR UPDATE`, RN-ADJ-09),
 * validan y devuelven el importe ya CALCULADO. Si algo falla lanzan `DomainException` sin tocar nada.
 */

/** Tipos de ajuste (RN-ADJ-01). Todos RESTAN de la cuenta: no hay recargos en el MVP. */
enum class AdjustmentKind { DISCOUNT_PERCENT, DISCOUNT_AMOUNT, COURTESY_ITEM, MANUAL_ADJUSTMENT }

/**
 * Lo que pide el personal. Según [kind] se usa UN solo dato (el resto debe venir en null; si no, el
 * pedido es incoherente → `ADJUSTMENT_INVALID`, como dice el contrato):
 * - `DISCOUNT_PERCENT`: [percentBp] en puntos básicos (1000 = 10 %).
 * - `DISCOUNT_AMOUNT` y `MANUAL_ADJUSTMENT`: [amount].
 * - `COURTESY_ITEM`: [itemLineTotal], el `lineTotalMinor` del ítem (RN-ADJ-04). Se obtiene con
 *   `CourtesyRules.lineTotalFor`, que responde `ADJUSTMENT_ITEM_INVALID` si el ítem está cancelado o
 *   ya tiene cortesía.
 */
data class AdjustmentRequest(
    val kind: AdjustmentKind,
    val percentBp: Int? = null,
    val amount: Money? = null,
    val itemLineTotal: Money? = null,
)

/**
 * Datos de la sesión necesarios para validar un ajuste.
 *
 * @property grossCharges cargos BRUTOS (antes de ajustes): base del límite del Mozo (RN-ADJ-06).
 * @property appliedAdjustments suma de ajustes `APPLIED` vigentes (los anulados no cuentan).
 * @property balance saldo actual (`charges − adjustments − paid`).
 * @property staffAppliedSoFar suma de lo que ESTE Mozo ya aplicó y sigue vigente, sin `apply_any`.
 * @property maxStaffDiscountPercent `maxStaffDiscountPercent` de los ajustes (por defecto 15, D-142).
 * @property maxStaffAbsolute `maxStaffAdjustmentMinor`: tope absoluto OPCIONAL (null = sin tope).
 * @property canApplyAny `true` si tiene `adjustment.apply_any` (el Dueño): no tiene límite.
 */
data class AdjustmentContext(
    val grossCharges: Money,
    val appliedAdjustments: Money,
    val balance: Balance,
    val staffAppliedSoFar: Money,
    val maxStaffDiscountPercent: Int,
    val maxStaffAbsolute: Money? = null,
    val canApplyAny: Boolean = false,
) {
    init {
        require(maxStaffDiscountPercent in 0..100) { "maxStaffDiscountPercent must be 0..100 (got $maxStaffDiscountPercent)" }
    }

    /**
     * Base sobre la que se calcula un descuento porcentual: `charges − adjustments` vigentes en este
     * instante (RN-ADJ-03). Nunca baja de cero.
     */
    fun percentBase(): Money =
        Money.of(maxOf(0L, Math.subtractExact(grossCharges.minor, appliedAdjustments.minor)))

    /**
     * Tope ACUMULADO del Mozo: `charges × porcentaje / 100`, redondeado hacia abajo (con enteros,
     * `total ≤ tope` equivale a `total × 100 ≤ charges × %`, así que no hay que elegir un redondeo).
     * Si existe tope absoluto, manda el menor de los dos.
     */
    fun staffLimit(): Money {
        val byPercent = Money.of(Math.multiplyExact(grossCharges.minor, maxStaffDiscountPercent.toLong()) / 100L)
        return maxStaffAbsolute?.let { minOf(byPercent, it) } ?: byPercent
    }
}

object AdjustmentRules {

    /**
     * Valida el ajuste y devuelve el importe CONGELADO que se guardará (RN-ADJ-03: no cambia aunque
     * lleguen pedidos nuevos después).
     *
     * Orden (si hay varios errores se informa el primero; el catálogo no fija prioridad, hallazgo H-24):
     * 1. Pedido coherente e importe > 0 → `ADJUSTMENT_INVALID`.
     * 2. Importe ≤ saldo → `ADJUSTMENT_EXCEEDS_BALANCE` (RN-ADJ-05: un ajuste nunca deja saldo negativo;
     *    a quien ya pagó se le compensa con un reembolso).
     * 3. Límite acumulado del Mozo → `ADJUSTMENT_LIMIT_EXCEEDED` (RN-ADJ-06).
     */
    fun validate(request: AdjustmentRequest, context: AdjustmentContext): Money {
        val amount = resolveAmount(request, context)

        val owed = context.balance.owed()
        if (amount > owed) {
            throw DomainException(
                ErrorCode.ADJUSTMENT_EXCEEDS_BALANCE,
                "Adjustment exceeds the current balance",
                mapOf("maxAdjustmentMinor" to owed.minor),
            )
        }

        if (!context.canApplyAny) {
            val limit = context.staffLimit()
            // Límite ACUMULADO: lo ya aplicado por este Mozo + el ajuste nuevo (evita el truco de
            // aplicar muchos descuentos chicos).
            if (context.staffAppliedSoFar + amount > limit) {
                throw DomainException(
                    ErrorCode.ADJUSTMENT_LIMIT_EXCEEDED,
                    "Adjustment exceeds the staff limit",
                    // `limitMinor`: el catálogo no define qué representa (H-19). Se informa el tope
                    // efectivo en centavos (el 15 % de los cargos brutos, o el tope absoluto si es menor).
                    mapOf("limitPercent" to context.maxStaffDiscountPercent, "limitMinor" to limit.minor),
                )
            }
        }
        return amount
    }

    /** Paso 1: comprueba la coherencia del pedido y calcula el importe según el tipo. */
    private fun resolveAmount(request: AdjustmentRequest, context: AdjustmentContext): Money {
        val amount: Money = when (request.kind) {
            DISCOUNT_PERCENT -> {
                val bp = request.percentBp
                if (bp == null || bp !in 1..10_000 || request.amount != null || request.itemLineTotal != null) {
                    throw invalid("DISCOUNT_PERCENT needs only percentBp between 1 and 10000")
                }
                // Mitad hacia arriba, al centavo más cercano (RN-ADJ-03), con la aritmética de `Money`.
                context.percentBase().percentBp(bp)
            }
            DISCOUNT_AMOUNT, MANUAL_ADJUSTMENT -> {
                if (request.percentBp != null || request.itemLineTotal != null) {
                    throw invalid("${request.kind} needs only amount")
                }
                request.amount ?: throw invalid("${request.kind} needs an amount")
            }
            COURTESY_ITEM -> {
                if (request.percentBp != null || request.amount != null) {
                    throw invalid("COURTESY_ITEM needs only the item")
                }
                request.itemLineTotal ?: throw invalid("COURTESY_ITEM needs the item line total")
            }
        }
        // La tabla exige `amount_minor > 0`; un porcentaje diminuto que redondea a 0 tampoco sirve.
        if (!amount.isPositive) throw invalid("Adjustment amount must be greater than zero")
        return amount
    }

    private fun invalid(message: String) = DomainException(ErrorCode.ADJUSTMENT_INVALID, message)
}
