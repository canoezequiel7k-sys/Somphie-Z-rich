package com.somphie.floor.application

import java.time.Instant
import java.util.UUID

interface StaffSessionOrdersPort {
    fun ordersForSession(sessionId: UUID, restaurantId: UUID): List<StaffSessionOrder>

    fun activityBySession(sessionIds: List<UUID>, restaurantId: UUID): Map<UUID, StaffSessionOrderActivity>

    fun closureActivity(sessionId: UUID, restaurantId: UUID): SessionClosureOrderActivity
}

interface ForcedSessionOrderCancellationPort {
    fun cancelOpenWork(
        sessionId: UUID,
        restaurantId: UUID,
        actorId: UUID,
        reason: String,
        now: Instant,
    )
}

interface ForcedSessionRefundPort {
    fun refundNegativeBalance(
        sessionId: UUID,
        restaurantId: UUID,
        actorId: UUID,
        reason: String,
        idempotencyKey: String,
    ): Long
}

data class StaffSessionOrderActivity(val pendingOrdersCount: Int, val hasItemsInService: Boolean)

data class SessionClosureOrderActivity(val pendingOrderIds: List<UUID>, val activeItemIds: List<UUID>)

data class StaffSessionOrder(
    val id: UUID,
    val sessionId: UUID,
    val displayCode: String,
    val status: String,
    val version: Long,
    val createdAt: Instant,
    val participantId: UUID?,
    val participantName: String?,
    val creatorUserId: UUID?,
    val creatorName: String?,
    val decidedAt: Instant?,
    val deciderId: UUID?,
    val deciderName: String?,
    val note: String?,
    val rejectionReason: String?,
    val rejectionText: String?,
    val totalMinor: Long,
    val items: List<StaffSessionOrderItem>,
)

data class StaffSessionOrderItem(
    val id: UUID,
    val productId: UUID,
    val productName: String,
    val productNameI18n: Map<String, String>,
    val basePriceMinor: Long,
    val optionsDeltaMinor: Long,
    val unitPriceMinor: Long,
    val quantity: Int,
    val lineTotalMinor: Long,
    val station: String,
    val status: String,
    val note: String?,
    val queuedAt: Instant?,
    val startedAt: Instant?,
    val readyAt: Instant?,
    val deliveredAt: Instant?,
    val cancelledAt: Instant?,
    val options: List<StaffSessionOrderOption>,
    val productCurrentlyAvailable: Boolean,
)

data class StaffSessionOrderOption(
    val groupId: UUID,
    val optionId: UUID,
    val groupName: String,
    val optionName: String,
    val priceDeltaMinor: Long,
)
