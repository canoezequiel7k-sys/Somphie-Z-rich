package com.somphie.floor.api

import com.somphie.audit.AuditService
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.sql.Timestamp
import java.util.UUID

@Component
class ExpireEmptySessionsJob(
    private val jdbc: NamedParameterJdbcTemplate,
    private val auditService: AuditService,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${somphie.floor.expiry-interval-ms:60000}")
    @Transactional
    fun runScheduled() {
        expireIdleSessionsAt(clock.instant())
    }

    @Transactional
    fun expireIdleSessionsAt(now: Instant): Int {
        // Una sola actualización condicional permite varias instancias sin doble cierre ni doble auditoría.
        val expired = jdbc.query(
            """
            UPDATE table_session ts
            SET status = 'CLOSED', closed_at = :now, close_reason = 'EXPIRED_EMPTY', join_code = NULL
            FROM restaurant_settings rs
            WHERE rs.restaurant_id = ts.restaurant_id
              AND ts.status = 'OPEN'
              AND ts.last_activity_at + (rs.session_idle_ttl_min * INTERVAL '1 minute') <= :now
              AND NOT EXISTS (
                  SELECT 1 FROM customer_order o
                  WHERE o.session_id = ts.id AND o.status = 'PENDING_REVIEW'
              )
              AND NOT EXISTS (
                  SELECT 1 FROM customer_order o
                  WHERE o.session_id = ts.id
                    AND o.status NOT IN ('REJECTED', 'EXPIRED', 'CANCELLED')
              )
              AND NOT EXISTS (
                  SELECT 1 FROM order_item oi
                  WHERE oi.session_id = ts.id
                    AND oi.status NOT IN ('CANCELLED', 'EXCLUDED')
              )
            RETURNING ts.id, ts.restaurant_id
            """.trimIndent(),
            mapOf("now" to Timestamp.from(now)),
        ) { rs, _ ->
            rs.getObject("id", UUID::class.java) to rs.getObject("restaurant_id", UUID::class.java)
        }
        expired.forEach { (sessionId, restaurantId) ->
            auditService.record(
                restaurantId = restaurantId.toString(),
                actorType = "SYSTEM",
                actorId = SYSTEM_ACTOR_ID.toString(),
                action = "SESSION_EXPIRED",
                targetType = "SESSION",
                targetId = sessionId.toString(),
                afterSummary = """{"closeReason":"EXPIRED_EMPTY"}""",
            )
        }
        return expired.size
    }

    @Scheduled(fixedDelayString = "\${somphie.floor.expiry-interval-ms:60000}")
    @Transactional
    fun cleanupExpiredJoinCodeAttempts() {
        cleanupExpiredJoinCodeAttemptsAt(clock.instant())
    }

    // La limpieza no toca filas de sesión: así no invierte el orden de locks del join.
    @Transactional
    fun cleanupExpiredJoinCodeAttemptsAt(now: Instant): Int =
        jdbc.update(
            """
            WITH expired AS (
                SELECT table_id, client_key
                FROM join_code_attempt
                WHERE window_started_at + INTERVAL '10 minutes' <= :now
                  AND (blocked_until IS NULL OR blocked_until <= :now)
                FOR UPDATE SKIP LOCKED
            )
            DELETE FROM join_code_attempt attempt
            USING expired
            WHERE attempt.table_id = expired.table_id
              AND attempt.client_key = expired.client_key
            """.trimIndent(),
            mapOf("now" to Timestamp.from(now)),
        )

    private companion object {
        // audit_event requiere UUID aun para SYSTEM y no vincula actorId con una cuenta.
        val SYSTEM_ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
}
