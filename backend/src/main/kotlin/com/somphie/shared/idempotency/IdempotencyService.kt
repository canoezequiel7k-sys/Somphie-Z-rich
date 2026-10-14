package com.somphie.shared.idempotency

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Service
class IdempotencyService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) {

    fun executeIdempotent(
        scope: String,
        idempotencyKey: String?,
        requestBody: String,
        action: () -> ResponseEntity<Map<String, Any>>,
    ): ResponseEntity<Map<String, Any>> {
        if (idempotencyKey.isNullOrBlank()) {
            throw DomainException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key header is required")
        }

        val requestHash = computeHash(requestBody)

        findExisting(scope, idempotencyKey)?.let { return replay(it, requestHash) }

        // La inserción atómica evita que dos reintentos concurrentes ejecuten la misma operación.
        val inserted = jdbc.update(
            """
            INSERT INTO idempotency_record (scope, key, request_hash, status)
            VALUES (:scope, :key, :hash, 'IN_PROGRESS')
            ON CONFLICT (scope, key) DO NOTHING
            """.trimIndent(),
            mapOf("scope" to scope, "key" to idempotencyKey, "hash" to requestHash)
        )
        if (inserted == 0) {
            val racedRequest = findExisting(scope, idempotencyKey)
                ?: throw DomainException(ErrorCode.IDEMPOTENCY_IN_PROGRESS, "Request with this idempotency key is already in progress")
            return replay(racedRequest, requestHash)
        }

        return try {
            val response = action()
            val status = response.statusCode.value()
            val body = response.body?.let(objectMapper::writeValueAsString) ?: ""

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

    private fun findExisting(scope: String, key: String): StoredRequest? =
        jdbc.query(
            """
            SELECT status, request_hash, response_status, response_body
            FROM idempotency_record WHERE scope = :scope AND key = :key
            """.trimIndent(),
            mapOf("scope" to scope, "key" to key),
        ) { rs, _ ->
            mapOf(
                "status" to rs.getString("status"),
                "request_hash" to rs.getString("request_hash"),
                "response_status" to rs.getInt("response_status"),
                "response_body" to rs.getString("response_body"),
            )
        }.firstOrNull()?.let {
            StoredRequest(
                it.getValue("status") as String,
                it.getValue("request_hash") as String,
                it.getValue("response_status") as Int,
                it["response_body"] as String?,
            )
        }

    private fun replay(existing: StoredRequest, requestHash: String): ResponseEntity<Map<String, Any>> {
        if (existing.requestHash != requestHash) {
            throw DomainException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency key reused with different request body")
        }
        if (existing.status == "IN_PROGRESS") {
            throw DomainException(ErrorCode.IDEMPOTENCY_IN_PROGRESS, "Request with this idempotency key is already in progress")
        }
        val responseBody = existing.responseBody
            ?.takeIf(String::isNotEmpty)
            ?.let { objectMapper.readValue(it, responseBodyType) }
        return ResponseEntity.status(existing.responseStatus)
            .header("Idempotent-Replayed", "true")
            .body(responseBody)
    }

    private data class StoredRequest(
        val status: String,
        val requestHash: String,
        val responseStatus: Int,
        val responseBody: String?,
    )

    private companion object {
        val responseBodyType = object : TypeReference<Map<String, Any>>() {}
    }
}
