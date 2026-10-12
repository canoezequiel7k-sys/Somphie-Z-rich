package com.somphie.orders.domain

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Motivos de rechazo permitidos (RN-ORD-09). */
enum class RejectionReason { OUT_OF_STOCK, KITCHEN_CLOSED, ITEM_UNAVAILABLE, OTHER }

/**
 * Rechazo de un pedido: motivo + texto opcional de hasta 140 caracteres (RN-ORD-09).
 * Se conserva en el historial para que el cliente vea por qué (CU-08, CU-10).
 */
data class Rejection(val reason: RejectionReason, val text: String? = null) {
    init {
        // El controlador ya validó el largo (maxLength del contrato); acá es una red de seguridad.
        require(text == null || text.length <= MAX_TEXT_LENGTH) { "Rejection text exceeds $MAX_TEXT_LENGTH characters" }
    }

    companion object {
        const val MAX_TEXT_LENGTH = 140
    }
}

/**
 * Agregado PEDIDO (`estados-y-transiciones.md` §2). Es inmutable: cada decisión devuelve un pedido
 * nuevo o lanza [DomainException] SIN modificar nada (regla "un error no cambia datos").
 *
 * Hasta `ACCEPTED` las transiciones son DECISIONES sobre el pedido (aceptar, rechazar, cancelar,
 * expirar). Una vez aceptado, el estado se DERIVA de los ítems con [OrderStatusDerivation]: acá no se
 * vuelve a escribir esa lógica.
 *
 * El reloj nunca se consulta: `now` lo inyecta la capa de aplicación (regla de oro 8).
 * Quién puede decidir (permisos), el motivo de exclusión/cancelación y la auditoría son de la capa
 * de aplicación; el dominio solo protege las reglas del pedido.
 *
 * Todavía NO delega las acciones de ítem (`start`, `deliver`…) ni recalcula el estado después de
 * ellas (RN-ORD-14): queda para la tanda siguiente.
 */
class Order private constructor(
    val id: UUID,
    val status: OrderStatus,
    val items: List<OrderItem>,
    val createdAt: Instant,
    /** Cuándo se aceptó. Define el orden FIFO de la cola de preparación (RN-PRE-08). */
    val acceptedAt: Instant?,
    /** Cuándo se tomó la decisión (aceptar, rechazar, cancelar o expirar). Sirve para `ORDER_ALREADY_DECIDED`. */
    val decidedAt: Instant?,
    val rejection: Rejection?,
) {

    /**
     * ¿Pasó el TTL sin decisión (RN-ORD-12)? El job de expiración usa esto para elegir pedidos.
     * Se considera vencido justo al cumplirse `ttl` (`>=`): el documento dice "tras N minutos".
     */
    fun isDueForExpiry(now: Instant, ttl: Duration): Boolean =
        status == OrderStatus.PENDING_REVIEW && Duration.between(createdAt, now) >= ttl

    /**
     * Acepta el pedido (RN-ORD-08, RN-ORD-10). Los ítems de [excludedItemIds] quedan `CANCELLED`
     * (no cobran) y el resto pasa a `QUEUED`.
     *
     * Orden de las comprobaciones: estado → ítems conocidos → no excluir todo.
     * Excluir todo es un error: para eso se usa rechazar (`ORDER_ALL_ITEMS_EXCLUDED`).
     */
    fun accept(now: Instant, excludedItemIds: Set<UUID> = emptySet()): Order {
        requirePendingForDecision()
        if (!items.map { it.id }.toSet().containsAll(excludedItemIds)) {
            throw DomainException(ErrorCode.ITEM_NOT_FOUND, "An excluded item does not belong to order $id")
        }
        if (excludedItemIds.size == items.size) {
            throw DomainException(ErrorCode.ORDER_ALL_ITEMS_EXCLUDED, "Every item of order $id was excluded")
        }
        val updated = items.map { if (it.id in excludedItemIds) it.exclude(now) else it.queue(now) }
        // El estado resultante sale de la derivación (§4), no de una regla repetida acá.
        val derived = OrderStatusDerivation.fromItems(updated.map { it.status })
        return Order(id, derived, updated, createdAt, acceptedAt = now, decidedAt = now, rejection = null)
    }

    /** Rechaza el pedido (RN-ORD-09). Sus ítems `PENDING` pasan a `CANCELLED` y no cobran (RN-ORD-19). */
    fun reject(now: Instant, rejection: Rejection): Order {
        requirePendingForDecision()
        return Order(id, OrderStatus.REJECTED, discardItems(now), createdAt, null, now, rejection)
    }

    /**
     * El cliente (autor/host) o el personal cancelan, solo mientras esté `PENDING_REVIEW` (RN-ORD-11).
     * Fuera de ese estado: `ORDER_NOT_CANCELLABLE` (así lo define el catálogo).
     */
    fun cancel(now: Instant): Order {
        if (status != OrderStatus.PENDING_REVIEW) {
            throw DomainException(
                ErrorCode.ORDER_NOT_CANCELLABLE,
                "Order $id cannot be cancelled from $status",
                mapOf("currentStatus" to status),
            )
        }
        return Order(id, OrderStatus.CANCELLED, discardItems(now), createdAt, null, now, null)
    }

    /**
     * Expira el pedido sin decisión (RN-ORD-12; lo llama un job).
     * - Ya decidido → `ORDER_ALREADY_DECIDED` (el job lo ignora): hallazgo H-27.
     * - Todavía no vencido → error de PROGRAMACIÓN (el job solo debe elegir pedidos vencidos).
     */
    fun expire(now: Instant, ttl: Duration): Order {
        if (status != OrderStatus.PENDING_REVIEW) throw alreadyDecided()
        require(isDueForExpiry(now, ttl)) { "Order $id is not due for expiry yet" }
        return Order(id, OrderStatus.EXPIRED, discardItems(now), createdAt, null, now, null)
    }

    /** Ítems `PENDING` de un pedido que no llegó a aceptarse: `PENDING → CANCELLED` (estados §3). */
    private fun discardItems(now: Instant): List<OrderItem> = items.map { it.exclude(now) }

    /**
     * Guarda común de aceptar y rechazar (RN-ORD-08: gana la primera decisión).
     * Un pedido `EXPIRED` responde `ORDER_EXPIRED`; cualquier otro estado, `ORDER_ALREADY_DECIDED`
     * (hallazgo H-26). El TTL NO se evalúa acá: lo aplica solo el job.
     */
    private fun requirePendingForDecision() {
        if (status == OrderStatus.PENDING_REVIEW) return
        if (status == OrderStatus.EXPIRED) {
            throw DomainException(ErrorCode.ORDER_EXPIRED, "Order $id has expired")
        }
        throw alreadyDecided()
    }

    /**
     * `decidedBy` (un nombre) lo agrega la capa de aplicación: el dominio solo conoce `currentStatus`
     * y `decidedAt`.
     */
    private fun alreadyDecided() = DomainException(
        ErrorCode.ORDER_ALREADY_DECIDED,
        "Order $id was already decided ($status)",
        buildMap<String, Any> {
            put("currentStatus", status)
            decidedAt?.let { put("decidedAt", it) }
        },
    )

    companion object {
        /**
         * Pedido nuevo (RN-ORD-05): nace `PENDING_REVIEW` con sus ítems `PENDING`.
         * Un pedido sin ítems es `ORDER_EMPTY`; ítems que no estén `PENDING` o con ids repetidos son
         * un error de programación.
         */
        fun create(id: UUID, items: List<OrderItem>, now: Instant): Order {
            if (items.isEmpty()) throw DomainException(ErrorCode.ORDER_EMPTY, "An order needs at least one item")
            require(items.all { it.status == ItemStatus.PENDING }) { "New order items must be PENDING" }
            require(items.map { it.id }.toSet().size == items.size) { "Order items must have distinct ids" }
            return Order(id, OrderStatus.PENDING_REVIEW, items.toList(), now, null, null, null)
        }

        /** Pedido tomado por el personal (RN-ORD-18): nace `ACCEPTED`. Reutiliza `create` + `accept`. */
        fun createAccepted(id: UUID, items: List<OrderItem>, now: Instant): Order =
            create(id, items, now).accept(now)
    }
}
