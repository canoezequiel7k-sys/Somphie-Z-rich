package com.somphie.events

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class EventRetentionJob(
    private val jdbc: JdbcTemplate,
) {
    @Scheduled(fixedDelayString = "\${somphie.events.retention-interval-ms:60000}")
    fun deleteExpiredEvents() {
        var deleted: Int
        var batches = 0
        do {
            deleted = jdbc.queryForObject(
                """
            WITH expired AS (
                SELECT restaurant_id, id
                FROM event_log
                WHERE occurred_at < now() - interval '48 hours'
                ORDER BY occurred_at
                LIMIT 1000
            ),
            deleted AS (
                DELETE FROM event_log e
                USING expired x
                WHERE e.restaurant_id = x.restaurant_id AND e.id = x.id
                RETURNING e.restaurant_id, e.id
            ),
            watermarked AS (
                INSERT INTO event_retention (restaurant_id, last_deleted_event_id)
                SELECT restaurant_id, MAX(id)
                FROM deleted
                GROUP BY restaurant_id
                ON CONFLICT (restaurant_id) DO UPDATE
                SET last_deleted_event_id = GREATEST(
                    event_retention.last_deleted_event_id,
                    EXCLUDED.last_deleted_event_id
                )
                RETURNING restaurant_id
            )
            SELECT COUNT(*) FROM deleted
                """.trimIndent(),
                Int::class.java,
            ) ?: 0
            batches++
        } while (deleted == BATCH_SIZE && batches < MAX_BATCHES)
    }

    private companion object {
        const val BATCH_SIZE = 1000
        const val MAX_BATCHES = 10
    }
}
