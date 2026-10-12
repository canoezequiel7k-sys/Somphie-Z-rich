package com.somphie.identity.infrastructure

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

class PasswordChangeRequiredFilterTest {
    private val filter = PasswordChangeRequiredFilter()

    @AfterEach
    fun clearAuthentication() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `temporary password blocks endpoints other than password change`() {
        authenticate(mustChangePassword = true)
        val response = MockHttpServletResponse()
        var continued = false

        filter.doFilter(
            MockHttpServletRequest("GET", "/me"),
            response,
            FilterChain { _, _ -> continued = true },
        )

        assertFalse(continued)
        assertEquals(403, response.status)
        assertTrue(response.contentAsString.contains("\"code\":\"PASSWORD_CHANGE_REQUIRED\""))
        assertTrue(response.contentType!!.startsWith("application/problem+json"))
    }

    @Test
    fun `temporary password can reach password change endpoint`() {
        authenticate(mustChangePassword = true)
        val response = MockHttpServletResponse()
        var continued = false
        val request = MockHttpServletRequest("POST", "/auth/change-password")

        filter.doFilter(
            request,
            response,
            FilterChain { _, _ -> continued = true },
        )

        assertEquals("", request.servletPath)
        assertEquals("/auth/change-password", request.requestURI)
        assertTrue(continued)
    }

    @Test
    fun `temporary password blocks other methods and similar routes`() {
        authenticate(mustChangePassword = true)

        listOf(
            MockHttpServletRequest("GET", "/auth/change-password"),
            MockHttpServletRequest("PUT", "/auth/change-password"),
            MockHttpServletRequest("POST", "/auth/change-password/"),
        ).forEach { request ->
            val response = MockHttpServletResponse()
            var continued = false

            filter.doFilter(request, response, FilterChain { _, _ -> continued = true })

            assertFalse(continued)
            assertEquals(403, response.status)
            assertTrue(response.contentAsString.contains("\"code\":\"PASSWORD_CHANGE_REQUIRED\""))
        }
    }

    @Test
    fun `regular account endpoints are not blocked`() {
        authenticate(mustChangePassword = false)
        var continued = false

        filter.doFilter(
            MockHttpServletRequest("GET", "/me"),
            MockHttpServletResponse(),
            FilterChain { _, _ -> continued = true },
        )

        assertTrue(continued)
    }

    private fun authenticate(mustChangePassword: Boolean) {
        val jwt = Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .claim("mustChangePassword", mustChangePassword)
            .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }
}
