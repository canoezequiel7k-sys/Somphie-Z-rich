package com.somphie.orders.domain

import com.somphie.shared.domain.Money
import com.somphie.shared.domain.total
import java.util.Collections
import java.util.UUID

/**
 * Una opción elegida en una línea, tal como estaba cuando se hizo el pedido (RN-ORD-04, RN-OPT-04).
 * Es una COPIA: si el catálogo cambia después, el pedido no se entera.
 * Es inmutable de verdad: solo contiene `String` y `Money` (un `Long`).
 */
data class OptionSnapshot(
    val groupName: String,
    val optionName: String,
    val priceDelta: Money,
)

/**
 * "Foto" de una línea de pedido en el momento de crearla (RN-ORD-04). Es un objeto de valor:
 * no tiene identidad propia y no cambia.
 *
 * INMUTABILIDAD REAL: `val` solo impide reasignar la referencia, no modificar el contenido de una
 * lista o un mapa que alguien más sigue teniendo. Por eso el constructor hace una COPIA DEFENSIVA de
 * [options] y [productNameI18n] y expone versiones de solo lectura en tiempo de ejecución (un cast a
 * `MutableList`/`MutableMap` lanza `UnsupportedOperationException`). Así, cambiar la colección original
 * después de crear el snapshot no altera ni sus datos ni sus importes derivados.
 *
 * Es una clase normal y NO una `data class` a propósito: `copy()` aceptaría colecciones mutables y se
 * saltearía la copia defensiva. Por eso `equals`, `hashCode` y `toString` están escritos a mano. Para
 * "modificar" un snapshot se crea otro con el constructor (siempre pasa por la copia defensiva).
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
class ItemSnapshot(
    val productId: UUID,
    val productName: String,
    productNameI18n: Map<String, String> = emptyMap(),
    val stationId: UUID,
    val quantity: Int,
    val note: String? = null,
    val basePrice: Money,
    options: List<OptionSnapshot> = emptyList(),
) {
    /** Traducciones del nombre: copia propia, de solo lectura, con el orden de inserción original. */
    val productNameI18n: Map<String, String> =
        Collections.unmodifiableMap(LinkedHashMap<String, String>(productNameI18n))

    /** Opciones elegidas: copia propia y de solo lectura. */
    val options: List<OptionSnapshot> = Collections.unmodifiableList(ArrayList<OptionSnapshot>(options))

    init {
        // Error de PROGRAMACIÓN (la capa de aplicación ya validó 1..maxQtyPerLine), no de negocio.
        require(quantity >= 1) { "Quantity must be at least 1 (got $quantity)" }
    }

    val optionsDelta: Money get() = this.options.map { it.priceDelta }.total()
    val unitPrice: Money get() = basePrice + optionsDelta
    val lineTotal: Money get() = unitPrice * quantity

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ItemSnapshot) return false
        return productId == other.productId &&
            productName == other.productName &&
            productNameI18n == other.productNameI18n &&
            stationId == other.stationId &&
            quantity == other.quantity &&
            note == other.note &&
            basePrice == other.basePrice &&
            options == other.options
    }

    override fun hashCode(): Int {
        var result = productId.hashCode()
        result = 31 * result + productName.hashCode()
        result = 31 * result + productNameI18n.hashCode()
        result = 31 * result + stationId.hashCode()
        result = 31 * result + quantity
        result = 31 * result + (note?.hashCode() ?: 0)
        result = 31 * result + basePrice.hashCode()
        result = 31 * result + options.hashCode()
        return result
    }

    override fun toString(): String =
        "ItemSnapshot(productId=$productId, productName=$productName, productNameI18n=$productNameI18n, " +
            "stationId=$stationId, quantity=$quantity, note=$note, basePrice=$basePrice, options=$options)"
}
