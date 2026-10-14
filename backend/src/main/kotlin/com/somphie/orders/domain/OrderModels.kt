package com.somphie.orders.domain

data class CreateOrderItemDto(
    val productId: String,
    val quantity: Int,
    val note: String? = null,
    val optionIds: List<String>? = null
)

data class CreateOrderRequest(
    val items: List<CreateOrderItemDto>,
    val note: String? = null,
    val acknowledgedTotalMinor: Long
)

data class CreateStaffOrderRequest(
    val items: List<CreateOrderItemDto>,
    val participantId: String? = null,
    val note: String? = null,
    val acknowledgedTotalMinor: Long? = null,
)
