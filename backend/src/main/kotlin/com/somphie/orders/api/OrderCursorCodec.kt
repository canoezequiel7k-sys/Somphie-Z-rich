package com.somphie.orders.api

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import java.nio.charset.StandardCharsets
import java.time.DateTimeException
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal data class OrderCursor(val createdAt: Instant, val id: UUID)

internal object OrderCursorCodec {
    fun decode(value: String): OrderCursor {
        val parts = try {
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split('|')
        } catch (_: IllegalArgumentException) {
            throw invalidCursor()
        }
        if (parts.size != 2) throw invalidCursor()
        val createdAt = try {
            Instant.parse(parts[0])
        } catch (_: DateTimeException) {
            throw invalidCursor()
        }
        val id = try {
            UUID.fromString(parts[1])
        } catch (_: IllegalArgumentException) {
            throw invalidCursor()
        }
        return OrderCursor(createdAt, id)
    }

    fun encode(createdAt: Instant, id: UUID): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$createdAt|$id".toByteArray(StandardCharsets.UTF_8))

    private fun invalidCursor() = DomainException(ErrorCode.VALIDATION_FAILED, "Invalid orders cursor")
}
