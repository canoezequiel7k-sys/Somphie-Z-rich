package com.somphie.shared.idempotency

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import javax.crypto.spec.SecretKeySpec

class GuestTokenIdempotencyResponseCodecTest {
    private val codec = GuestTokenIdempotencyResponseCodec(
        SecretKeySpec(ByteArray(32) { it.toByte() }, "HmacSHA256"),
    )

    @Test
    fun `guest token is encrypted in storage and restored for replay`() {
        val response = mapOf("id" to "session-id", "guestToken" to "gt_secret-token")

        val stored = codec.encode(response)

        assertNotEquals(response, stored)
        assertFalse(stored.getValue("guestToken").toString().contains("gt_secret-token"))
        assertEquals(response, codec.decode(stored))
    }

    @Test
    fun `plaintext guest token in a stored response is rejected`() {
        assertThrows(IllegalStateException::class.java) {
            codec.decode(mapOf("guestToken" to "gt_plaintext"))
        }
    }
}
