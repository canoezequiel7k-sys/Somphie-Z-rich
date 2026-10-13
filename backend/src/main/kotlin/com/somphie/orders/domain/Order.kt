package com.somphie.orders.domain

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.domain.Money
import java.time.Duration
import java.time.Instant
import java.util.Collections
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
 * Las acciones de ítem se delegan a [OrderItem] y el estado agregado se deriva otra vez después de
 * cada transición, como exige RN-ORD-14.
 */
class Order private constructor(
    val id: UUID,
    val status: OrderStatus,
    items: List<OrderItem>,
    val createdAt: Instant,
    /** Cuándo se aceptó. Define el orden FIFO de la cola de preparación (RN-PRE-08). */
    val acceptedAt: Instant?,
    /** Cuándo se tomó la decisión (aceptar, rechazar, cancelar o expirar). Sirve para `ORDER_ALREADY_DECIDED`. */
    val decidedAt: Instant?,
    val rejection: Rejection?,
    val version: Long,
) {

    /**
     * Ítems del pedido: copia propia y de solo lectura en tiempo de ejecución. Se copia en el constructor,
     * que es el único camino para crear un `Order`, así que ni la lista que entregó quien llamó ni un
     * cast a `MutableList` pueden alterarla.
     */
    val items: List<OrderItem> = Collections.unmodifiableList(ArrayList<OrderItem>(items))

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
        return Order(id, derived, updated, createdAt, acceptedAt = now, decidedAt = now, rejection = null, version = version + 1)
    }

    /** Rechaza el pedido (RN-ORD-09). Sus ítems `PENDING` pasan a `CANCELLED` y no cobran (RN-ORD-19). */
    fun reject(now: Instant, rejection: Rejection): Order {
        requirePendingForDecision()
        return Order(id, OrderStatus.REJECTED, discardItems(now), createdAt, null, now, rejection, version + 1)
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
        return Order(id, OrderStatus.CANCELLED, discardItems(now), createdAt, null, now, null, version + 1)
    }

    /**
     * Expira el pedido sin decisión (RN-ORD-12; lo llama un job).
     * - Ya decidido → `ORDER_ALREADY_DECIDED` (el job lo ignora): hallazgo H-27.
     * - Todavía no vencido → error de PROGRAMACIÓN (el job solo debe elegir pedidos vencidos).
     */
    fun expire(now: Instant, ttl: Duration): Order {
        if (status != OrderStatus.PENDING_REVIEW) throw alreadyDecided()
        require(isDueForExpiry(now, ttl)) { "Order $id is not due for expiry yet" }
        return Order(id, OrderStatus.EXPIRED, discardItems(now), createdAt, null, now, null, version + 1)
    }

    /** Delega el inicio al ítem y recalcula el estado agregado desde todos sus ítems. */
    fun startItem(itemId: UUID, now: Instant): Order = transitionItem(itemId) { it.start(now) }

    /** Reutiliza la transición existente del ítem, incluido el atajo QUEUED → READY. */
    fun markItemReady(itemId: UUID, now: Instant): Order = transitionItem(itemId) { it.markReady(now) }

    /** Delega la entrega al ítem y actualiza el pedido sin duplicar la regla de transición. */
    fun deliverItem(itemId: UUID, now: Instant): Order = transitionItem(itemId) { it.deliver(now) }

    /** Entrega en una sola mutación todos los ítems que estaban listos; la versión sube una vez. */
    fun deliverReadyItems(now: Instant): Order {
        val ready = items.filter { it.status == ItemStatus.READY }
        if (ready.isEmpty()) return this
        val updatedItems = items.map { if (it.status == ItemStatus.READY) it.deliver(now) else it }
        return Order(
            id = id,
            status = OrderStatusDerivation.fromItems(updatedItems.map { it.status }),
            items = updatedItems,
            createdAt = createdAt,
            acceptedAt = acceptedAt,
            decidedAt = decidedAt,
            rejection = rejection,
            version = version + 1,
        )
    }

    /** Delega la cancelación al ítem; el motivo y la autorización siguen siendo responsabilidad de aplicación. */
    fun cancelItem(itemId: UUID, now: Instant): Order = transitionItem(itemId) { it.cancel(now) }

    /** Delega el deshacer al ítem para conservar su ventana temporal y sus errores existentes. */
    fun undoItem(itemId: UUID, now: Instant, undoWindow: Duration): Order =
        transitionItem(itemId) { it.undo(now, undoWindow) }

    /**
     * Expone solo los importes admitidos por RN-BIL-01 para que `OrderChargesPort` los entregue a billing.
     * Se filtra en el módulo dueño de los estados, así billing no necesita importar `Order` ni sus ítems.
     */
    fun billableLineTotals(): List<Money> =
        if (status !in BILLABLE_STATUSES) emptyList()
        else items.filter { it.status != ItemStatus.CANCELLED }.map { it.lineTotal }

    /**
     * Reemplaza un solo ítem en una copia local y publica un agregado nuevo únicamente si la regla tuvo éxito.
     * Así una transición inválida nunca deja cambios parciales en el pedido original.
     */
    private fun transitionItem(itemId: UUID, transition: (OrderItem) -> OrderItem): Order {
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) {
            throw DomainException(ErrorCode.ITEM_NOT_FOUND, "Item $itemId does not belong to order $id")
        }
        val updatedItems = items.toMutableList()
        updatedItems[index] = transition(items[index])
        val derivedStatus = OrderStatusDerivation.fromItems(updatedItems.map { it.status })
        return Order(
            id = id,
            status = derivedStatus,
            items = updatedItems,
            createdAt = createdAt,
            acceptedAt = acceptedAt,
            decidedAt = decidedAt,
            rejection = rejection,
            version = version + 1,
        )
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
        /** Estados de pedido que generan cargos; la cuenta consume los importes ya filtrados por esta raíz. */
        private val BILLABLE_STATUSES = setOf(
            OrderStatus.ACCEPTED,
            OrderStatus.IN_PREPARATION,
            OrderStatus.READY,
            OrderStatus.DELIVERED,
        )

        /**
         * Pedido nuevo (RN-ORD-05): nace `PENDING_REVIEW` con sus ítems `PENDING`.
         * Un pedido sin ítems es `ORDER_EMPTY`; ítems que no estén `PENDING` o con ids repetidos son
         * un error de programación.
         */
        fun create(id: UUID, items: List<OrderItem>, now: Instant): Order {
            if (items.isEmpty()) throw DomainException(ErrorCode.ORDER_EMPTY, "An order needs at least one item")
            require(items.all { it.status == ItemStatus.PENDING }) { "New order items must be PENDING" }
            require(items.map { it.id }.toSet().size == items.size) { "Order items must have distinct ids" }
            return Order(id, OrderStatus.PENDING_REVIEW, items, now, null, null, null, 1)
        }

        fun restore(
            id: UUID,
            status: OrderStatus,
            items: List<OrderItem>,
            createdAt: Instant,
            acceptedAt: Instant?,
            decidedAt: Instant?,
            rejection: Rejection?,
            version: Long,
        ): Order {
            require(version >= 1) { "Order version must be positive" }
            require(items.map { it.id }.toSet().size == items.size) { "Order items must have distinct ids" }
            if (status in setOf(OrderStatus.ACCEPTED, OrderStatus.IN_PREPARATION, OrderStatus.READY, OrderStatus.DELIVERED, OrderStatus.CANCELLED) &&
                items.any { it.status == ItemStatus.PENDING }
            ) {
                throw IllegalArgumentException("Accepted order cannot contain pending items")
            }
            if (status in setOf(OrderStatus.ACCEPTED, OrderStatus.IN_PREPARATION, OrderStatus.READY, OrderStatus.DELIVERED) &&
                OrderStatusDerivation.fromItems(items.map { it.status }) != status
            ) {
                throw IllegalArgumentException("Persisted order status does not match its items")
            }
            return Order(id, status, items, createdAt, acceptedAt, decidedAt, rejection, version)
        }

        /** Pedido tomado por el personal (RN-ORD-18): nace `ACCEPTED`. Reutiliza `create` + `accept`. */
        fun createAccepted(id: UUID, items: List<OrderItem>, now: Instant): Order =
            create(id, items, now).accept(now)
    }
}
