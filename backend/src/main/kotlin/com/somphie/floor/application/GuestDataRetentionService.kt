package com.somphie.floor.application

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

data class GuestDataRetentionResult(
    val participantNamesAnonymized: Int,
    val waiterCallsDeleted: Int,
)

@Service
class GuestDataRetentionService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val clock: Clock,
) {
    @Transactional
    fun applyRetention(): GuestDataRetentionResult {
        val now = clock.instant()
        val participantCutoff = now.atOffset(ZoneOffset.UTC).minusMonths(PARTICIPANT_NAME_RETENTION_MONTHS).toInstant()
        val waiterCallCutoff = now.minus(WAITER_CALL_RETENTION)

        val participantsAnonymized = jdbc.update(
            """
            UPDATE session_participant
            SET display_name = :anonymizedName
            WHERE user_id IS NULL
              AND joined_at <= :cutoff
              AND display_name <> :anonymizedName
            """.trimIndent(),
            mapOf(
                "anonymizedName" to ANONYMIZED_NAME,
                "cutoff" to java.sql.Timestamp.from(participantCutoff),
            ),
        )
        val waiterCallsDeleted = jdbc.update(
            """
            DELETE FROM waiter_call
            WHERE created_at <= :cutoff
            """.trimIndent(),
            mapOf("cutoff" to java.sql.Timestamp.from(waiterCallCutoff)),
        )
        return GuestDataRetentionResult(participantsAnonymized, waiterCallsDeleted)
    }

    private companion object {
        const val ANONYMIZED_NAME = "Anónimo"
        const val PARTICIPANT_NAME_RETENTION_MONTHS = 12L
        val WAITER_CALL_RETENTION: Duration = Duration.ofDays(30)
    }
}
