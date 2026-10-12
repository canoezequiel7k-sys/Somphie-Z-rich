package com.somphie.shared.api

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

/** Verifica que errores de negocio e inesperados se expongan como Problem+JSON seguro. */
class ApiExceptionHandlerTest {
    private val handler = ApiExceptionHandler()

    /** Usa el status documentado, preserva el código y omite el mensaje interno del dominio. */
    @Test
    fun `domain errors preserve catalog code without leaking message`() {
        MDC.put("traceId", "trace-test")
        try {
            val response = handler.handleDomain(
                DomainException(ErrorCode.FORBIDDEN, "internal authorization detail", mapOf("scope" to "restaurant")),
            )
            assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
            assertEquals(MediaType.valueOf("application/problem+json"), response.headers.contentType)
            assertEquals("FORBIDDEN", response.body?.code)
            assertEquals("trace-test", response.body?.traceId)
            assertNull(response.body?.detail)
            assertEquals(mapOf("scope" to "restaurant"), response.body?.extra)
        } finally {
            MDC.remove("traceId")
        }
    }

    /** Devuelve un código genérico y no filtra el texto de una excepción imprevista. */
    @Test
    fun `unexpected errors hide internal messages`() {
        val response = handler.handleUnexpected(IllegalStateException("private diagnostic"))
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertEquals("INTERNAL_ERROR", response.body?.code)
        assertNull(response.body?.detail)
    }

    @Test
    fun `login lockout preserves retry interval in response header`() {
        val response = handler.handleDomain(
            DomainException(ErrorCode.ACCOUNT_TEMPORARILY_LOCKED, "private login state", mapOf("retryAfterSec" to 42)),
        )

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode)
        assertEquals("ACCOUNT_TEMPORARILY_LOCKED", response.body?.code)
        assertEquals("42", response.headers.getFirst("Retry-After"))
        assertNull(response.body?.detail)
    }
}
