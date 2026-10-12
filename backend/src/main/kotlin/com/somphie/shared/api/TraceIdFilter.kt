package com.somphie.shared.api

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/** Asigna un identificador por solicitud para correlacionar respuestas de error y logs. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceIdFilter : OncePerRequestFilter() {
    /** Genera el identificador en el servidor para no confiar en valores enviados por el cliente. */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val traceId = UUID.randomUUID().toString()
        MDC.put(TRACE_ID_KEY, traceId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(TRACE_ID_KEY)
        }
    }

    private companion object {
        const val TRACE_ID_KEY = "traceId"
    }
}
