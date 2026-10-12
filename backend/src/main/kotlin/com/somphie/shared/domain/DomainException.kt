package com.somphie.shared.domain

/**
 * Códigos de error de negocio.
 *
 * Deben coincidir EXACTAMENTE con `docs/02-dominio/catalogo-errores.md` y con el enum del contrato
 * (`docs/04-api/openapi.yaml`). Un código se agrega aquí solo cuando un caso de uso lo produce:
 * no se inventan (AGENTS.md §2, regla 2).
 */
enum class ErrorCode {
    // --- Pedidos (catálogo §4) ---
    ORDER_EMPTY,
    ORDER_ALREADY_DECIDED,
    ORDER_NOT_CANCELLABLE,
    ORDER_EXPIRED,
    ORDER_ALL_ITEMS_EXCLUDED,
    ITEM_NOT_FOUND,

    // --- Ítems de pedido (catálogo §4) ---
    ITEM_INVALID_TRANSITION,
    ITEM_NOT_CANCELLABLE,
    ITEM_UNDO_WINDOW_EXPIRED,

    // --- Pagos (catálogo §5) ---
    PAYMENT_AMOUNT_INVALID,
    PAYMENT_EXCEEDS_BALANCE,
    PAYMENT_NOTHING_TO_PAY,

    // --- Propina (catálogo §7.1, v0.2) ---
    TIP_INVALID,
    TIP_EXCEEDS_LIMIT,
    TIP_NOT_ALLOWED,

    // --- Ajustes de cuenta (catálogo §7.1, v0.2) ---
    ADJUSTMENT_INVALID,
    ADJUSTMENT_EXCEEDS_BALANCE,
    ADJUSTMENT_LIMIT_EXCEEDED,
    ADJUSTMENT_NOT_VOIDABLE,
    ADJUSTMENT_ITEM_INVALID,

    // --- Anulación y reembolso de pagos (catálogo §5) ---
    PAYMENT_NOT_VOIDABLE,
    REFUND_EXCEEDS_PAID,

    // --- Genérico (catálogo §1): falta de permiso o de alcance sobre el recurso ---
    FORBIDDEN,
}

/**
 * Error de negocio. El manejador global (Fase 2) lo convierte en Problem+JSON usando [code] y [extra];
 * el mensaje es solo para logs y nunca llega al cliente.
 */
class DomainException(
    val code: ErrorCode,
    message: String,
    val extra: Map<String, Any> = emptyMap(),
) : RuntimeException(message)
