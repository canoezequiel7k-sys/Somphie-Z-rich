package com.somphie.audit

import org.slf4j.MDC
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class AuditService(
    private val jdbc: NamedParameterJdbcTemplate
) {

    fun record(
        restaurantId: String,
        actorType: String,
        actorId: String,
        action: String,
        targetType: String,
        targetId: String,
        reason: String? = null,
        beforeSummary: String? = null,
        afterSummary: String? = null
    ) {
        val traceId = MDC.get("traceId") ?: UUID.randomUUID().toString()
        val eventId = UUID.randomUUID().toString()

        val sql = """
            INSERT INTO audit_event (id, restaurant_id, occurred_at, actor_type, actor_id, action, target_type, target_id, reason, before_summary, after_summary, trace_id)
            VALUES (CAST(:id AS UUID), CAST(:restaurantId AS UUID), :occurredAt, :actorType, CAST(:actorId AS UUID), :action, :targetType, CAST(:targetId AS UUID), :reason, CAST(:beforeSummary AS JSONB), CAST(:afterSummary AS JSONB), :traceId)
        """

        // RN-AUD usa nombres de dominio; V7 conserva sus valores de almacenamiento históricos.
        val persistedActorType = when (actorType) {
            "USER", "STAFF" -> "STAFF"
            "PARTICIPANT", "CUSTOMER" -> "CUSTOMER"
            "SYSTEM" -> "SYSTEM"
            else -> throw IllegalArgumentException("Unsupported audit actor type: $actorType")
        }
        jdbc.update(sql, mapOf(
            "id" to eventId,
            "restaurantId" to restaurantId,
            "occurredAt" to java.sql.Timestamp.from(Instant.now()),
            "actorType" to persistedActorType,
            "actorId" to actorId,
            "action" to action,
            "targetType" to targetType,
            "targetId" to targetId,
            "reason" to reason,
            "beforeSummary" to beforeSummary,
            "afterSummary" to afterSummary,
            "traceId" to traceId
        ))
    }
}
