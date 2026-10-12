package com.somphie.billing.domain

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.domain.Money
import java.util.UUID

/**
 * Cortesía de un ítem (RN-ADJ-04): apunta a un ítem ACEPTADO, NO cancelado y sin cortesía previa.
 * Recibe una proyección del puerto entre módulos, no la entidad `OrderItem`.
 *
 * Un ítem `PENDING` todavía no es un cargo: su pedido no fue aceptado (RN-BIL-01) y puede terminar
 * rechazado, cancelado o vencido. Una cortesía sobre él restaría plata de OTROS ítems que sí se cobran
 * (hallazgo H-29). Un ítem `PENDING` solo existe mientras su pedido está `PENDING_REVIEW`, por eso
 * mirar el estado del ítem alcanza: no hace falta conocer el pedido.
 *
 * Función pura. La capa de aplicación le pasa los ítems que ya tienen una cortesía VIGENTE (`APPLIED`;
 * una anulada no cuenta, RN-ADJ-07/08) y usa el resultado como `AdjustmentRequest.itemLineTotal`.
 */
object CourtesyRules {

    /** Vista mínima de un ítem que la aplicación adapta desde `orders` antes de validar la cortesía. */
    data class ItemFacts(
        val id: UUID,
        val lineTotal: Money,
        val accepted: Boolean,
        val cancelled: Boolean,
    )

    /**
     * Devuelve el importe de la cortesía o lanza `ADJUSTMENT_ITEM_INVALID` si el ítem no fue aceptado,
     * está cancelado o ya tiene cortesía.
     */
    fun lineTotalFor(item: ItemFacts, itemIdsWithCourtesy: Set<UUID>): Money {
        if (!item.accepted || item.cancelled || item.id in itemIdsWithCourtesy) {
            throw DomainException(
                ErrorCode.ADJUSTMENT_ITEM_INVALID,
                "Item ${item.id} cannot receive a courtesy",
                mapOf("itemId" to item.id),
            )
        }
        return item.lineTotal
    }
}
