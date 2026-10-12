package com.somphie.floor.domain

import java.time.Instant

data class PublicTable(
    val tableLabel: String,
    val restaurantId: String,
    val restaurantOpen: Boolean,
    val hasActiveSession: Boolean,
    val canOpen: Boolean
)

data class OpenSessionRequest(
    val displayName: String
)

data class JoinSessionRequest(
    val displayName: String,
    val joinCode: String
)

data class SessionInfoDto(
    val id: String,
    val status: String,
    val tableLabel: String,
    val openedAt: Instant,
    val readOnly: Boolean = false
)

data class ParticipantInfoDto(
    val id: String,
    val displayName: String,
    val role: String
)

data class SessionAccessResponse(
    val session: SessionInfoDto,
    val participant: ParticipantInfoDto,
    val guestToken: String,
    val joinCode: String? = null
)
