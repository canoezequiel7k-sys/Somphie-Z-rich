package com.somphie.shared.domain

/**
 * Códigos de error de negocio.
 *
 * Deben coincidir EXACTAMENTE con `docs/02-dominio/catalogo-errores.md` y con el enum del contrato
 * (`docs/04-api/openapi.yaml`). Un código se agrega aquí solo cuando un caso de uso lo produce:
 * no se inventan (AGENTS.md §2, regla 2).
 */
enum class ErrorCode {
    ITEM_INVALID_TRANSITION,
    ITEM_NOT_CANCELLABLE,
    ITEM_UNDO_WINDOW_EXPIRED,
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
