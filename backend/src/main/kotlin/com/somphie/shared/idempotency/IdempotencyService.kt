package com.somphie.shared.idempotency

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Service
class IdempotencyService(
    private val jdbc: NamedParameterJdbcTemplate
) {

    fun <T : Any> executeIdempotent(
        scope: String,
        idempotencyKey: String?,
        requestBody: String,
        action: () -> ResponseEntity<T>
    ): ResponseEntity<T> {
        if (idempotencyKey.isNullOrBlank()) {
            throw DomainException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key header is required")
        }

        val requestHash = computeHash(requestBody)

        val existing = jdbc.query(
            "SELECT status, request_hash, response_status, response_body FROM idempotency_record WHERE scope = :scope AND key = :key",
            mapOf("scope" to scope, "key" to idempotencyKey)
        ) { rs, _ ->
            mapOf(
                "status" to rs.getString("status"),
                "request_hash" to rs.getString("request_hash"),
                "response_status" to rs.getInt("response_status"),
                "response_body" to rs.getString("response_body")
            )
        }.firstOrNull()

        if (existing != null) {
            val status = existing["status"] as String
            val storedHash = existing["request_hash"] as String

            if (storedHash != requestHash) {
                throw DomainException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency key reused with different request body")
            }

            if (status == "IN_PROGRESS") {
                throw DomainException(ErrorCode.IDEMPOTENCY_IN_PROGRESS, "Request with this idempotency key is already in progress")
            }

            val responseStatus = existing["response_status"] as Int
            @Suppress("UNCHECKED_CAST")
            val responseBody = existing["response_body"] as? T ?: (null as T)

            return ResponseEntity.status(responseStatus)
                .header("Idempotent-Replayed", "true")
                .body(responseBody)
        }

        jdbc.update(
            "INSERT INTO idempotency_record (scope, key, request_hash, status) VALUES (:scope, :key, :hash, 'IN_PROGRESS')",
            mapOf("scope" to scope, "key" to idempotencyKey, "hash" to requestHash)
        )

        return try {
            val response = action()
            val status = response.statusCode.value()
            val body = response.body?.toString() ?: ""

            jdbc.update(
                "UPDATE idempotency_record SET status = 'COMPLETED', response_status = :status, response_body = :body WHERE scope = :scope AND key = :key",
                mapOf("scope" to scope, "key" to idempotencyKey, "status" to status, "body" to body)
            )

            response
        } catch (e: Exception) {
            jdbc.update(
                "DELETE FROM idempotency_record WHERE scope = :scope AND key = :key",
                mapOf("scope" to scope, "key" to idempotencyKey)
            )
            throw e
        }
    }

    private fun computeHash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
