package com.somphie.identity.domain

import java.util.UUID

data class StaffAccount(
    val id: UUID,
    val restaurantId: UUID,
    val email: String,
    val passwordHash: String,
    val displayName: String,
    val status: String,
    val tokenVersion: Long,
    val mustChangePassword: Boolean,
)

data class StaffStation(
    val id: UUID,
    val code: String,
    val name: String,
)

data class StaffProfile(
    val account: StaffAccount,
    val roles: List<String>,
    val permissions: List<String>,
    val stations: List<StaffStation>,
)

data class StaffAuthResult(
    val profile: StaffProfile,
    val accessToken: String,
    val refreshToken: String,
)

sealed interface RefreshRotation {
    data class Rotated(val profile: StaffProfile) : RefreshRotation
    data object Invalid : RefreshRotation
    data object Reused : RefreshRotation
}
