package com.somphie.identity.api

import com.somphie.identity.domain.StaffProfile
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class LoginRequest(
    @field:NotBlank
    @field:Email
    val email: String,
    @field:NotBlank
    val password: String,
)

data class RefreshRequest(
    @field:NotBlank
    val refreshToken: String,
)

data class ChangePasswordRequest(
    @field:NotBlank
    val currentPassword: String,
    @field:NotBlank
    val newPassword: String,
)

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSec: Int = 900,
)

data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSec: Int,
    val user: MeResponse,
)

data class MeResponse(
    val id: String,
    val email: String,
    val displayName: String,
    val roles: List<String>,
    val permissions: List<String>,
    val stations: List<StationResponse>,
    val mustChangePassword: Boolean,
) {
    companion object {
        fun from(profile: StaffProfile) = MeResponse(
            id = profile.account.id.toString(),
            email = profile.account.email,
            displayName = profile.account.displayName,
            roles = profile.roles,
            permissions = profile.permissions,
            stations = profile.stations.map { StationResponse(it.id.toString(), it.code, it.name) },
            mustChangePassword = profile.account.mustChangePassword,
        )
    }
}

data class StationResponse(
    val id: String,
    val code: String,
    val name: String,
)
