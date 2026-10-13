package com.somphie.identity.infrastructure

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtValidationException
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.jwk.source.ImmutableSecret
import java.net.URI
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@Configuration
class IdentitySecurityConfiguration {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(12)

    @Bean
    fun jwtSecretKey(@Value("\${somphie.jwt.secret}") encodedSecret: String): SecretKey {
        val key = try {
            Base64.getDecoder().decode(encodedSecret)
        } catch (exception: IllegalArgumentException) {
            throw IllegalStateException("JWT_SECRET must be Base64-encoded", exception)
        }
        require(key.size >= 32) { "JWT_SECRET must contain at least 256 bits" }
        return SecretKeySpec(key, "HmacSHA256")
    }

    @Bean
    fun jwtEncoder(secretKey: SecretKey): JwtEncoder =
        NimbusJwtEncoder(ImmutableSecret<SecurityContext>(secretKey))

    @Bean
    fun jwtDecoder(secretKey: SecretKey): JwtDecoder {
        val delegate = NimbusJwtDecoder.withSecretKey(secretKey)
            .macAlgorithm(MacAlgorithm.HS256)
            .build()
        delegate.setJwtValidator(JwtValidators.createDefaultWithIssuer(JWT_ISSUER))
        return JwtDecoder { token ->
            try {
                delegate.decode(token)
            } catch (exception: JwtValidationException) {
                if (exception.errors.any { it.description?.contains("expired", ignoreCase = true) == true }) {
                    throw ExpiredAccessTokenException(exception)
                }
                throw exception
            }
        }
    }

    @Bean
    fun jwtAuthenticationConverter(): JwtAuthenticationConverter =
        JwtAuthenticationConverter().apply {
            setJwtGrantedAuthoritiesConverter { jwt ->
                jwt.getClaimAsStringList("perms").orEmpty().map(::SimpleGrantedAuthority)
            }
        }

    @Bean
    fun identitySecurityFilterChain(
        http: HttpSecurity,
        jwtDecoder: JwtDecoder,
        jwtAuthenticationConverter: JwtAuthenticationConverter,
    ): SecurityFilterChain {
        val entryPoint = IdentityAuthenticationEntryPoint()
        val defaultBearerTokenResolver = DefaultBearerTokenResolver()
        val bearerTokenResolver = BearerTokenResolver { request ->
            val token = defaultBearerTokenResolver.resolve(request)
            val path = request.requestURI.removePrefix(request.contextPath)
            if (token?.startsWith(GUEST_TOKEN_PREFIX) == true || isGuestEndpoint(request.method, path)) {
                null
            } else {
                token
            }
        }
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.POST, "/auth/login", "/auth/refresh").permitAll()
                it.requestMatchers("/auth/logout", "/auth/change-password", "/me").authenticated()
                it.requestMatchers(HttpMethod.GET, "/staff/orders", "/staff/orders/*")
                    .hasAuthority("order.read")
                it.requestMatchers(HttpMethod.POST, "/staff/orders/*/accept")
                    .hasAuthority("order.accept")
                it.requestMatchers(HttpMethod.POST, "/staff/orders/*/reject")
                    .hasAuthority("order.reject")
                it.requestMatchers(HttpMethod.GET, "/staff/stations")
                    .hasAuthority("item.prepare")
                it.requestMatchers(HttpMethod.GET, "/staff/stations/*/queue")
                    .hasAuthority("item.prepare")
                it.requestMatchers(HttpMethod.POST, "/staff/order-items/*/start", "/staff/order-items/*/ready")
                    .hasAuthority("item.prepare")
                it.requestMatchers(HttpMethod.POST, "/staff/order-items/*/deliver", "/staff/orders/*/deliver-ready")
                    .hasAuthority("item.deliver")
                it.requestMatchers(HttpMethod.POST, "/staff/order-items/*/cancel")
                    .hasAuthority("order.cancel")
                it.requestMatchers(HttpMethod.POST, "/staff/order-items/*/undo")
                    .hasAuthority("item.undo")
                it.requestMatchers(HttpMethod.POST, "/staff/tables/*/sessions")
                    .hasAuthority("session.open_manual")
                it.requestMatchers(HttpMethod.POST, "/staff/sessions/*/join-code/rotate")
                    .hasAuthority("participant.revoke")
                it.requestMatchers(HttpMethod.POST, "/staff/sessions/*/participants/*/revoke")
                    .hasAuthority("participant.revoke")
                it.requestMatchers("/staff/**").denyAll()
                it.requestMatchers(HttpMethod.GET, "/menu", "/products/*", "/public/tables/*", "/media/**", "/version", "/actuator/health")
                    .permitAll()
                it.requestMatchers(HttpMethod.POST, "/public/tables/*/sessions", "/public/tables/*/join")
                    .permitAll()
                it.requestMatchers(HttpMethod.POST, "/sessions/current/orders", "/orders/*/cancel")
                    .permitAll()
                it.requestMatchers(HttpMethod.DELETE, "/sessions/current/participants/*")
                    .permitAll()
                it.requestMatchers(HttpMethod.GET, "/sessions/current/orders", "/orders/*")
                    .permitAll()
                it.anyRequest().authenticated()
            }
            .oauth2ResourceServer {
                it.bearerTokenResolver(bearerTokenResolver)
                it.jwt { jwt ->
                    jwt.decoder(jwtDecoder)
                        .jwtAuthenticationConverter(jwtAuthenticationConverter)
                }
                it.authenticationEntryPoint(entryPoint)
            }
            .exceptionHandling { it.authenticationEntryPoint(entryPoint) }
            .addFilterAfter(
                PasswordChangeRequiredFilter(),
                org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter::class.java,
            )
        return http.build()
    }

    private fun isGuestEndpoint(method: String, path: String): Boolean =
        (path == "/sessions/current/orders" && method in setOf("GET", "POST")) ||
            (path.matches(Regex("^/sessions/current/participants/[^/]+$")) && method == "DELETE") ||
            (path.matches(Regex("^/orders/[^/]+$")) && method == "GET") ||
            (path.matches(Regex("^/orders/[^/]+/cancel$")) && method == "POST")

    private companion object {
        const val JWT_ISSUER = "somphie-backend"
        const val GUEST_TOKEN_PREFIX = "gt_"
    }
}

private class ExpiredAccessTokenException(cause: Throwable) : JwtException("Access token expired", cause)

private class IdentityAuthenticationEntryPoint : AuthenticationEntryPoint {
    override fun commence(
        request: jakarta.servlet.http.HttpServletRequest,
        response: jakarta.servlet.http.HttpServletResponse,
        authException: AuthenticationException,
    ) {
        val code = if (hasExpiredTokenCause(authException)) "TOKEN_EXPIRED" else "UNAUTHENTICATED"
        response.status = 401
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.setHeader("WWW-Authenticate", "Bearer")
        val problemType = URI.create("https://api.somphiezurich.example/errors/${code.lowercase().replace('_', '-')}")
        val traceId = org.slf4j.MDC.get("traceId") ?: ""
        response.writer.write(
            """{"type":"$problemType","title":"No se pudo autenticar la solicitud","status":401,"code":"$code","traceId":"$traceId"}""",
        )
    }

    private fun hasExpiredTokenCause(exception: Throwable?): Boolean {
        var current = exception
        while (current != null) {
            if (current is ExpiredAccessTokenException) return true
            current = current.cause
        }
        return false
    }
}
