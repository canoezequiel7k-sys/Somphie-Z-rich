package com.somphie.identity.api

import com.somphie.identity.application.IdentityService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
class IdentityController(
    private val identityService: IdentityService,
) {
    @PostMapping("/auth/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        servletRequest: HttpServletRequest,
    ): LoginResponse {
        val result = identityService.login(request.email, request.password, servletRequest.remoteAddr)
        return LoginResponse(
            accessToken = result.accessToken,
            refreshToken = result.refreshToken,
            expiresInSec = 900,
            user = MeResponse.from(result.profile),
        )
    }

    @PostMapping("/auth/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): TokenResponse {
        val result = identityService.refresh(request.refreshToken)
        return TokenResponse(result.accessToken, result.refreshToken)
    }

    @PostMapping("/auth/logout")
    fun logout(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: RefreshRequest,
    ): ResponseEntity<Void> {
        identityService.logout(jwt.subject, jwt.getClaim("rid"), jwt.getClaim("tv"), request.refreshToken)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/auth/change-password")
    fun changePassword(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: ChangePasswordRequest,
    ): ResponseEntity<Void> {
        identityService.changePassword(
            jwt.subject,
            jwt.getClaim("rid"),
            jwt.getClaim("tv"),
            request.currentPassword,
            request.newPassword,
        )
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal jwt: Jwt): MeResponse =
        MeResponse.from(identityService.me(jwt.subject, jwt.getClaim("rid"), jwt.getClaim("tv")))
}
