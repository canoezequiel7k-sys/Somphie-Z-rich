package com.somphie.floor.api

import com.somphie.floor.domain.ParticipantInfoDto
import com.somphie.floor.domain.SessionAccessResponse
import com.somphie.floor.domain.SessionInfoDto
import com.somphie.shared.domain.ErrorCode
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

sealed interface JoinSessionResult {
    data class Joined(val response: SessionAccessResponse) : JoinSessionResult
    data class Rejected(val code: ErrorCode, val extra: Map<String, Any> = emptyMap()) : JoinSessionResult
}

@Service
class JoinSessionService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val clock: Clock,
) {
    private data class ActiveSession(val id: UUID, val joinCode: String, val tableLabel: String)
    private data class AttemptState(val failureCount: Int, val windowStartedAt: Instant, val blockedUntil: Instant?)

    @Transactional
    fun join(tableCode: String, displayName: String, submittedCode: String, remoteAddress: String): JoinSessionResult {
        val tableId = jdbc.query(
            "SELECT id FROM dining_table WHERE table_code = :tableCode",
            mapOf("tableCode" to tableCode),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }.firstOrNull()
            ?: return JoinSessionResult.Rejected(ErrorCode.SESSION_CLOSED)

        val now = clock.instant()
        val clientKey = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(remoteAddress.toByteArray(StandardCharsets.UTF_8)),
        )
        jdbc.update(
            """
            INSERT INTO join_code_attempt (table_id, client_key, window_started_at)
            VALUES (:tableId, :clientKey, :now)
            ON CONFLICT (table_id, client_key) DO NOTHING
            """.trimIndent(),
            mapOf("tableId" to tableId, "clientKey" to clientKey, "now" to java.sql.Timestamp.from(now)),
        )
        // El bloqueo por mesa e IP hace que los fallos simultáneos no eludan el umbral.
        val attempt = jdbc.query(
            """
            SELECT failure_count, window_started_at, blocked_until
            FROM join_code_attempt
            WHERE table_id = :tableId AND client_key = :clientKey
            FOR UPDATE
            """.trimIndent(),
            mapOf("tableId" to tableId, "clientKey" to clientKey),
        ) { rs, _ ->
            AttemptState(
                rs.getInt("failure_count"),
                rs.getTimestamp("window_started_at").toInstant(),
                rs.getTimestamp("blocked_until")?.toInstant(),
            )
        }.single()

        if (attempt.blockedUntil?.isAfter(now) == true) {
            return limited(attempt.blockedUntil, now)
        }

        val windowExpired = !now.isBefore(attempt.windowStartedAt.plus(FAILURE_WINDOW))
        if (windowExpired || attempt.blockedUntil != null) {
            jdbc.update(
                """
                UPDATE join_code_attempt
                SET failure_count = 0, window_started_at = :now, blocked_until = NULL
                WHERE table_id = :tableId AND client_key = :clientKey
                """.trimIndent(),
                mapOf("tableId" to tableId, "clientKey" to clientKey, "now" to java.sql.Timestamp.from(now)),
            )
        }

        val session = jdbc.query(
            """
            SELECT ts.id, ts.join_code, dt.label AS table_label
            FROM table_session ts
            JOIN dining_table dt ON dt.id = ts.table_id
            WHERE ts.table_id = :tableId AND ts.status = 'OPEN'
            FOR UPDATE OF ts
            """.trimIndent(),
            mapOf("tableId" to tableId),
        ) { rs, _ ->
            ActiveSession(
                rs.getObject("id", UUID::class.java),
                rs.getString("join_code"),
                rs.getString("table_label"),
            )
        }.firstOrNull() ?: return JoinSessionResult.Rejected(ErrorCode.SESSION_CLOSED)

        if (!MessageDigest.isEqual(
                session.joinCode.toByteArray(StandardCharsets.US_ASCII),
                submittedCode.toByteArray(StandardCharsets.US_ASCII),
            )
        ) {
            val previousFailures = if (windowExpired || attempt.blockedUntil != null) 0 else attempt.failureCount
            val failures = previousFailures + 1
            val blockedUntil = if (failures >= MAX_FAILURES) now.plus(BLOCK_DURATION) else null
            jdbc.update(
                """
                UPDATE join_code_attempt
                SET failure_count = :failures, window_started_at = :windowStartedAt, blocked_until = :blockedUntil
                WHERE table_id = :tableId AND client_key = :clientKey
                """.trimIndent(),
                mapOf(
                    "tableId" to tableId,
                    "clientKey" to clientKey,
                    "failures" to failures,
                    "windowStartedAt" to java.sql.Timestamp.from(
                        if (windowExpired || attempt.blockedUntil != null) now else attempt.windowStartedAt,
                    ),
                    "blockedUntil" to blockedUntil?.let(java.sql.Timestamp::from),
                ),
            )
            return if (blockedUntil != null) limited(blockedUntil, now)
            else JoinSessionResult.Rejected(ErrorCode.JOIN_CODE_INVALID)
        }

        jdbc.update(
            """
            UPDATE join_code_attempt
            SET failure_count = 0, window_started_at = :now, blocked_until = NULL
            WHERE table_id = :tableId AND client_key = :clientKey
            """.trimIndent(),
            mapOf("tableId" to tableId, "clientKey" to clientKey, "now" to java.sql.Timestamp.from(now)),
        )
        jdbc.update(
            "UPDATE table_session SET last_activity_at = :now WHERE id = :sessionId AND status = 'OPEN'",
            mapOf("sessionId" to session.id, "now" to java.sql.Timestamp.from(now)),
        )
        val hostCount = jdbc.queryForObject(
            "SELECT COUNT(1) FROM session_participant WHERE session_id = :sessionId AND role = 'HOST' AND revoked_at IS NULL",
            mapOf("sessionId" to session.id),
            Int::class.java,
        ) ?: 0
        val participantId = UUID.randomUUID()
        val role = if (hostCount == 0) "HOST" else "MEMBER"
        val guestToken = generateGuestToken()
        val tokenHash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(guestToken.toByteArray(StandardCharsets.UTF_8)),
        )
        jdbc.update(
            """
            INSERT INTO session_participant (id, session_id, display_name, role, token_hash, joined_at)
            VALUES (:participantId, :sessionId, :displayName, :role, :tokenHash, :joinedAt)
            """.trimIndent(),
            mapOf(
                "participantId" to participantId,
                "sessionId" to session.id,
                "displayName" to displayName,
                "role" to role,
                "tokenHash" to tokenHash,
                "joinedAt" to java.sql.Timestamp.from(now),
            ),
        )
        return JoinSessionResult.Joined(
            SessionAccessResponse(
                session = SessionInfoDto(session.id.toString(), "OPEN", session.tableLabel, now),
                participant = ParticipantInfoDto(participantId.toString(), displayName, role),
                guestToken = guestToken,
            ),
        )
    }

    private fun limited(blockedUntil: Instant, now: Instant): JoinSessionResult.Rejected {
        val remaining = Duration.between(now, blockedUntil)
        val retryAfter = remaining.seconds + if (remaining.nano > 0) 1 else 0
        return JoinSessionResult.Rejected(
            ErrorCode.JOIN_CODE_RATE_LIMITED,
            mapOf("retryAfterSec" to retryAfter),
        )
    }

    private fun generateGuestToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return "gt_" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val MAX_FAILURES = 5
        val FAILURE_WINDOW: Duration = Duration.ofMinutes(10)
        val BLOCK_DURATION: Duration = Duration.ofMinutes(10)
        val secureRandom = java.security.SecureRandom()
    }
}
