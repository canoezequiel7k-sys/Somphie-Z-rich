package com.somphie.orders.domain

data class ExcludedItemDto(
    val itemId: String,
    val reason: String
)

data class AcceptOrderRequest(
    val excludedItems: List<ExcludedItemDto>? = null
)

data class RejectOrderRequest(
    val reasonCode: String,
    val reasonText: String? = null
)
