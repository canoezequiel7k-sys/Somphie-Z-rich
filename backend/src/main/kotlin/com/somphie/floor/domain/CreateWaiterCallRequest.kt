package com.somphie.floor.domain

import jakarta.validation.constraints.Size

data class CreateWaiterCallRequest(
    val reason: String,
    @field:Size(max = 80)
    val text: String? = null,
)
