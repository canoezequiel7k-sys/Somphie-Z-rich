package com.somphie.shared.domain

/**
 * Códigos de error de negocio.
 *
 * Deben coincidir EXACTAMENTE con `docs/02-dominio/catalogo-errores.md` y con el enum del contrato
 * (`docs/04-api/openapi.yaml`). Un código se agrega aquí solo cuando un caso de uso lo produce:
 * no se inventan (AGENTS.md §2, regla 2).
 */
enum class ErrorCode {
    // --- Identidad (catálogo §2) ---
    INVALID_CREDENTIALS,
    ACCOUNT_DISABLED,
    ACCOUNT_TEMPORARILY_LOCKED,
    TOKEN_EXPIRED,
    UNAUTHENTICATED,
    PASSWORD_TOO_WEAK,
    REFRESH_TOKEN_REUSED,
    PASSWORD_CHANGE_REQUIRED,

    // --- Pedidos (catálogo §4) ---
    ORDER_EMPTY,
    ORDER_ALREADY_DECIDED,
    ORDER_NOT_CANCELLABLE,
    ORDER_EXPIRED,
    ORDER_ALL_ITEMS_EXCLUDED,
    ORDER_NOT_FOUND,
    ITEM_NOT_FOUND,

    // --- Ítems de pedido (catálogo §4) ---
    ITEM_INVALID_TRANSITION,
    ITEM_NOT_CANCELLABLE,
    ITEM_UNDO_WINDOW_EXPIRED,
    ITEM_STATION_FORBIDDEN,

    // --- Opciones de catálogo (catálogo §7.1) ---
    OPTION_NOT_FOUND,
    OPTION_UNAVAILABLE,
    OPTION_SELECTION_INVALID,
    PRODUCT_UNAVAILABLE,
    PRICE_CHANGED,

    // --- Sesiones y participantes (catálogo §3) ---
    SESSION_FULL, // La sesión alcanzó el máximo de participantes permitido.
    SESSION_CLOSED, // La acción requiere que la sesión siga abierta.
    SESSION_ALREADY_OPEN,
    TABLE_UNAVAILABLE,
    JOIN_CODE_INVALID,
    JOIN_CODE_RATE_LIMITED,
    SESSION_ACCESS_REVOKED,
    SESSION_HAS_PENDING_ORDERS, // El cierre normal no permite pedidos aún sin decisión.
    SESSION_HAS_ACTIVE_ITEMS, // El cierre normal no permite ítems en preparación o listos.
    SESSION_BALANCE_PENDING, // El cierre normal requiere saldo exactamente cero.
    PARTICIPANT_NOT_FOUND, // El participante indicado no pertenece a la sesión.
    HOST_CANNOT_BE_REVOKED, // El host no puede revocar su propio acceso.
    WAITER_CALL_ALREADY_ACTIVE,
    WAITER_CALL_COOLDOWN,
    WAITER_CALL_NOT_FOUND,
    STATE_CONFLICT,

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
    VALIDATION_FAILED,

    // --- Recursos y Catálogo (catálogo §1, §4) ---
    RESOURCE_NOT_FOUND,
    PRODUCT_NOT_FOUND,
    CATEGORY_NOT_FOUND,

    // --- Idempotencia (catálogo §1) ---
    IDEMPOTENCY_KEY_REQUIRED,
    IDEMPOTENCY_KEY_REUSED,
    IDEMPOTENCY_IN_PROGRESS,
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
