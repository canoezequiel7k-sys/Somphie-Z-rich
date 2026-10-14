package com.somphie.floor.application

import java.util.UUID

interface SessionBillPort {
    fun billForSession(sessionId: UUID, restaurantId: UUID): Map<String, Any>

    fun billSummariesForSessions(sessionIds: List<UUID>, restaurantId: UUID): Map<UUID, SessionBillSummary>
}

data class SessionBillSummary(val chargesMinor: Long, val balanceMinor: Long)
