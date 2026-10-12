package com.somphie.shared.api

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Convierte fallos no previstos en Problem+JSON sin exponer mensajes internos ni trazas. */
@RestControllerAdvice
class ApiExceptionHandler {
    /** Conserva el código de negocio y el estado HTTP definido en el catálogo de errores. */
    @ExceptionHandler(DomainException::class)
    fun handleDomain(exception: DomainException): ResponseEntity<ApiProblem> {
        val status = exception.code.httpStatus()
        val code = exception.code.name
        return response(
            ApiProblem(
                type = URI.create("$ERRORS_BASE/${code.lowercase().replace('_', '-')}"),
                title = "No se pudo completar la operación",
                status = status.value(),
                code = code,
                traceId = MDC.get(TRACE_ID_KEY),
                extra = exception.extra.ifEmpty { null },
            ),
            status,
        )
    }

    /** Devuelve 400 con un código estable cuando el cuerpo no se puede interpretar como JSON. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMalformedRequest(): ResponseEntity<ApiProblem> = response(
        ApiProblem(
            type = URI.create("$ERRORS_BASE/malformed-request"),
            title = "Solicitud inválida",
            status = HttpStatus.BAD_REQUEST.value(),
            code = "MALFORMED_REQUEST",
            traceId = MDC.get(TRACE_ID_KEY),
        ),
        HttpStatus.BAD_REQUEST,
    )

    /** Registra solo metadatos seguros y devuelve un error estable con el traceId de la solicitud. */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(exception: Exception): ResponseEntity<ApiProblem> {
        val traceId = MDC.get(TRACE_ID_KEY)
        logger.error("Error HTTP no previsto: traceId={}, type={}", traceId, exception.javaClass.simpleName)
        return response(
            ApiProblem(
                type = URI.create("$ERRORS_BASE/internal-error"),
                title = "Algo salió mal",
                status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
                code = INTERNAL_ERROR_CODE,
                traceId = traceId,
            ),
            HttpStatus.INTERNAL_SERVER_ERROR,
        )
    }

    /** Reutiliza el mismo media type para que todos los errores respeten RFC 7807. */
    private fun response(problem: ApiProblem, status: HttpStatus): ResponseEntity<ApiProblem> =
        ResponseEntity.status(status)
            .contentType(PROBLEM_JSON)
            .body(problem)

    /** Traduce únicamente los estados definidos para los errores que el dominio ya puede producir. */
    private fun ErrorCode.httpStatus(): HttpStatus = when (this) {
        ErrorCode.ORDER_EMPTY,
        ErrorCode.ORDER_ALL_ITEMS_EXCLUDED,
        ErrorCode.PAYMENT_AMOUNT_INVALID,
        ErrorCode.TIP_INVALID,
        ErrorCode.TIP_EXCEEDS_LIMIT,
        ErrorCode.ADJUSTMENT_INVALID,
        -> HttpStatus.UNPROCESSABLE_CONTENT
        ErrorCode.ITEM_NOT_FOUND,
        ErrorCode.PARTICIPANT_NOT_FOUND,
        -> HttpStatus.NOT_FOUND
        ErrorCode.FORBIDDEN,
        ErrorCode.ADJUSTMENT_LIMIT_EXCEEDED,
        -> HttpStatus.FORBIDDEN
        ErrorCode.ORDER_ALREADY_DECIDED,
        ErrorCode.ORDER_NOT_CANCELLABLE,
        ErrorCode.ORDER_EXPIRED,
        ErrorCode.ITEM_INVALID_TRANSITION,
        ErrorCode.ITEM_NOT_CANCELLABLE,
        ErrorCode.ITEM_UNDO_WINDOW_EXPIRED,
        ErrorCode.SESSION_FULL,
        ErrorCode.SESSION_CLOSED,
        ErrorCode.SESSION_HAS_PENDING_ORDERS,
        ErrorCode.SESSION_HAS_ACTIVE_ITEMS,
        ErrorCode.SESSION_BALANCE_PENDING,
        ErrorCode.HOST_CANNOT_BE_REVOKED,
        ErrorCode.PAYMENT_EXCEEDS_BALANCE,
        ErrorCode.PAYMENT_NOTHING_TO_PAY,
        ErrorCode.TIP_NOT_ALLOWED,
        ErrorCode.ADJUSTMENT_EXCEEDS_BALANCE,
        ErrorCode.ADJUSTMENT_NOT_VOIDABLE,
        ErrorCode.ADJUSTMENT_ITEM_INVALID,
        ErrorCode.PAYMENT_NOT_VOIDABLE,
        ErrorCode.REFUND_EXCEEDS_PAID,
        -> HttpStatus.CONFLICT
    }

    private companion object {
        val logger = LoggerFactory.getLogger(ApiExceptionHandler::class.java)
        val PROBLEM_JSON = MediaType.valueOf("application/problem+json")
        const val ERRORS_BASE = "https://api.somphiezurich.example/errors"
        const val INTERNAL_ERROR_CODE = "INTERNAL_ERROR"
        const val TRACE_ID_KEY = "traceId"
    }
}
