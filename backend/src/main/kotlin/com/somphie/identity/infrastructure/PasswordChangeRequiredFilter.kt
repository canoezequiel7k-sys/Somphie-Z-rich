package com.somphie.identity.infrastructure

import com.somphie.shared.api.ApiProblem
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URI

class PasswordChangeRequiredFilter(
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
        val mustChangePassword = authentication?.token?.getClaimAsBoolean("mustChangePassword") == true
        val isPasswordChangeRequest =
            request.method == "POST" && request.requestURI == request.contextPath + PASSWORD_CHANGE_PATH
        if (!mustChangePassword || isPasswordChangeRequest) {
            filterChain.doFilter(request, response)
            return
        }

        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        val problem = ApiProblem(
            type = URI.create("$ERRORS_BASE/password-change-required"),
            title = "Debés cambiar tu contraseña para continuar",
            status = HttpStatus.FORBIDDEN.value(),
            code = "PASSWORD_CHANGE_REQUIRED",
            traceId = MDC.get(TRACE_ID_KEY),
        )
        response.writer.write(
            """{"type":"${problem.type}","title":"${problem.title}","status":${problem.status},"code":"${problem.code}","traceId":"${problem.traceId.orEmpty()}"}""",
        )
    }

    private companion object {
        const val PASSWORD_CHANGE_PATH = "/auth/change-password"
        const val ERRORS_BASE = "https://api.somphiezurich.example/errors"
        const val TRACE_ID_KEY = "traceId"
    }
}
