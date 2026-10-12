package com.somphie.orders.domain

import com.somphie.shared.domain.Money
import com.somphie.shared.domain.total
import java.util.UUID

/**
 * Una opción elegida en una línea, tal como estaba cuando se hizo el pedido (RN-ORD-04, RN-OPT-04).
 * Es una COPIA: si el catálogo cambia después, el pedido no se entera.
 */
data class OptionSnapshot(
    val groupName: String,
    val optionName: String,
    val priceDelta: Money,
)

/**
 * "Foto" de una línea de pedido en el momento de crearla (RN-ORD-04). Es un objeto de valor:
 * no tiene identidad propia y no cambia (todas sus propiedades son `val`).
 *
 * Los precios DERIVADOS se calculan acá y solo acá (regla de oro 3: una sola definición):
 * - `optionsDelta = Σ priceDelta` de las opciones.
 * - `unitPrice = basePrice + optionsDelta` (RN-OPT-04).
 * - `lineTotal = unitPrice × quantity` (RN-BIL-01). Es el importe que billing suma a la cuenta.
 *
 * Las validaciones que dependen de la configuración (máx. de líneas, cantidad máxima, máx. 10 opciones,
 * largo de la nota) son de la capa de aplicación (RN-ORD-02). Acá solo se protege lo que no puede
 * pasar nunca: una cantidad menor a 1.
 */
data class ItemSnapshot(
    val productId: UUID,
    val productName: String,
    val productNameI18n: Map<String, String> = emptyMap(),
    val stationId: UUID,
    val quantity: Int,
    val note: String? = null,
    val basePrice: Money,
    val options: List<OptionSnapshot> = emptyList(),
) {
    init {
        // Error de PROGRAMACIÓN (la capa de aplicación ya validó 1..maxQtyPerLine), no de negocio.
        require(quantity >= 1) { "Quantity must be at least 1 (got $quantity)" }
    }

    val optionsDelta: Money get() = options.map { it.priceDelta }.total()
    val unitPrice: Money get() = basePrice + optionsDelta
    val lineTotal: Money get() = unitPrice * quantity
}
