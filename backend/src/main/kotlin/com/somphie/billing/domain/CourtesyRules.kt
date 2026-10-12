package com.somphie.billing.domain

import com.somphie.orders.domain.ItemStatus
import com.somphie.orders.domain.OrderItem
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.domain.Money
import java.util.UUID

/**
 * Cortesía de un ítem (RN-ADJ-04): apunta a un ítem ACEPTADO, NO cancelado y sin cortesía previa.
 * Cierra el hallazgo H-25: ahora el dominio conoce el id, el estado y el importe del ítem.
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

    /**
     * Devuelve el importe de la cortesía (el `lineTotal` del ítem) o lanza `ADJUSTMENT_ITEM_INVALID`
     * si el ítem está cancelado, aún sin aceptar (`PENDING`) o ya tiene cortesía.
     */
    fun lineTotalFor(item: OrderItem, itemIdsWithCourtesy: Set<UUID>): Money {
        if (item.status == ItemStatus.CANCELLED || item.status == ItemStatus.PENDING || item.id in itemIdsWithCourtesy) {
            throw DomainException(
                ErrorCode.ADJUSTMENT_ITEM_INVALID,
                "Item ${item.id} cannot receive a courtesy",
                mapOf("itemId" to item.id),
            )
        }
        return item.lineTotal
    }
}
