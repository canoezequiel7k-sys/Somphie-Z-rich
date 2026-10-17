package com.somphie.shared.idempotency

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.http.ResponseEntity
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec

class IdempotencyServiceTest {
    private val jdbc = mock(NamedParameterJdbcTemplate::class.java)
    private val codec = GuestTokenIdempotencyResponseCodec(
        SecretKeySpec(ByteArray(32) { it.toByte() }, "HmacSHA256"),
    )
    private val service = IdempotencyService(jdbc, ObjectMapper(), codec)

    @Test
    fun `missing key is rejected before invoking the action or database`() {
        var invoked = false

        val error = assertThrows(DomainException::class.java) {
            service.executeIdempotent("scope", " ", "request") {
                invoked = true
                ResponseEntity.ok(mapOf("result" to "result"))
            }
        }

        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, error.code)
        assertEquals(false, invoked)
        org.mockito.Mockito.verifyNoInteractions(jdbc)
    }

    @Test
    fun `completed matching request replays the stored response without running action`() {
        given(jdbc.query(anyString(), anyMap<String, Any>(), any(RowMapper::class.java)))
            .willReturn(
                listOf(
                    mapOf(
                        "status" to "COMPLETED",
                        "request_hash" to hash("request"),
                        "response_status" to 201,
                        "response_body" to """{"id":"created","count":1}""",
                    ),
                ),
            )
        var invoked = false

        val response = service.executeIdempotent("scope", "key", "request") {
            invoked = true
            ResponseEntity.ok(mapOf("result" to "new"))
        }

        assertEquals(201, response.statusCode.value())
        assertEquals(mapOf("id" to "created", "count" to 1), response.body)
        assertEquals("true", response.headers.getFirst("Idempotent-Replayed"))
        assertEquals(false, invoked)
    }

    @Test
    fun `key reuse with changed content and an in progress request are rejected`() {
        given(jdbc.query(anyString(), anyMap<String, Any>(), any(RowMapper::class.java)))
            .willReturn(
                listOf(
                    mapOf(
                        "status" to "COMPLETED",
                        "request_hash" to hash("different"),
                        "response_status" to 200,
                        "response_body" to "unused",
                    ),
                ),
            )
        assertEquals(
            ErrorCode.IDEMPOTENCY_KEY_REUSED,
            assertThrows(DomainException::class.java) {
                service.executeIdempotent("scope", "key", "request") { ResponseEntity.ok(mapOf("unused" to true)) }
            }.code,
        )

        given(jdbc.query(anyString(), anyMap<String, Any>(), any(RowMapper::class.java)))
            .willReturn(
                listOf(
                    mapOf(
                        "status" to "IN_PROGRESS",
                        "request_hash" to hash("request"),
                        "response_status" to 0,
                        "response_body" to "",
                    ),
                ),
            )
        assertEquals(
            ErrorCode.IDEMPOTENCY_IN_PROGRESS,
            assertThrows(DomainException::class.java) {
                service.executeIdempotent("scope", "key", "request") { ResponseEntity.ok(mapOf("unused" to true)) }
            }.code,
        )
    }

    @Test
    fun `failed action removes its reservation and propagates the original exception`() {
        given(jdbc.query(anyString(), anyMap<String, Any>(), any(RowMapper::class.java)))
            .willReturn(emptyList())
        given(jdbc.update(anyString(), anyMap<String, Any>())).willReturn(1)
        val failure = IllegalStateException("operation failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            service.executeIdempotent("scope", "key", "request") { throw failure }
        }

        assertSame(failure, thrown)
        verify(jdbc, times(2)).update(anyString(), anyMap<String, Any>())
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
