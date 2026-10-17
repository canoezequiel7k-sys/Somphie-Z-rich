package com.somphie.shared.idempotency

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Service
class IdempotencyRetentionService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val clock: Clock,
) {
    @Transactional
    fun deleteExpiredRecords(): Int {
        val cutoff = clock.instant().minus(RECORD_RETENTION)
        return jdbc.update(
            """
            DELETE FROM idempotency_record
            WHERE created_at <= :cutoff
            """.trimIndent(),
            mapOf("cutoff" to java.sql.Timestamp.from(cutoff)),
        )
    }

    private companion object {
        val RECORD_RETENTION: Duration = Duration.ofHours(24)
    }
}
