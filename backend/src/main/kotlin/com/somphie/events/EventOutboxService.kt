package com.somphie.events

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class DomainEventType(val wireValue: String) {
    ORDER_CREATED("order.created"),
    ORDER_UPDATED("order.updated"),
    ITEM_UPDATED("item.updated"),
    SESSION_UPDATED("session.updated"),
    SESSION_CLOSED("session.closed"),
    BILL_UPDATED("bill.updated"),
    CATALOG_UPDATED("catalog.updated"),
    TABLE_UPDATED("table.updated"),
    WAITER_CALLED("waiter.called"),
    WAITER_UPDATED("waiter.updated"),
}

enum class EventAggregateType {
    ORDER,
    ITEM,
    SESSION,
    TABLE,
    CATALOG,
}

sealed interface EventVersion {
    data class Persisted(val value: Long) : EventVersion

    data object Increment : EventVersion
}

data class DomainEvent(
    val restaurantId: UUID,
    val type: DomainEventType,
    val aggregateType: EventAggregateType,
    val aggregateId: UUID,
    val version: EventVersion,
    val sessionId: UUID? = null,
    val stationId: UUID? = null,
)

data class StoredDomainEvent(
    val eventId: Long,
    val type: DomainEventType,
    val aggregateType: EventAggregateType,
    val aggregateId: UUID,
    val version: Long,
    val sessionId: UUID?,
    val stationId: UUID?,
    val occurredAt: Instant,
)

@Service
class EventOutboxService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val clock: Clock,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun append(event: DomainEvent): StoredDomainEvent {
        val version = when (val source = event.version) {
            is EventVersion.Persisted -> advancePersistedVersion(event, source.value)
            EventVersion.Increment -> incrementVersion(event)
        }
        val eventId = nextEventId(event.restaurantId)
        val occurredAt = Instant.now(clock)
        jdbc.update(
            """
            INSERT INTO event_log (
                restaurant_id, id, type, aggregate_type, aggregate_id, version,
                session_id, station_id, occurred_at
            )
            VALUES (
                :restaurantId, :eventId, :type, :aggregateType, :aggregateId, :version,
                :sessionId, :stationId, :occurredAt
            )
            """.trimIndent(),
            mapOf(
                "restaurantId" to event.restaurantId,
                "eventId" to eventId,
                "type" to event.type.wireValue,
                "aggregateType" to event.aggregateType.name,
                "aggregateId" to event.aggregateId,
                "version" to version,
                "sessionId" to event.sessionId,
                "stationId" to event.stationId,
                "occurredAt" to java.sql.Timestamp.from(occurredAt),
            ),
        )
        return StoredDomainEvent(
            eventId = eventId,
            type = event.type,
            aggregateType = event.aggregateType,
            aggregateId = event.aggregateId,
            version = version,
            sessionId = event.sessionId,
            stationId = event.stationId,
            occurredAt = occurredAt,
        )
    }

    private fun nextEventId(restaurantId: UUID): Long {
        jdbc.update(
            """
            INSERT INTO event_cursor (restaurant_id) VALUES (:restaurantId)
            ON CONFLICT (restaurant_id) DO NOTHING
            """.trimIndent(),
            mapOf("restaurantId" to restaurantId),
        )
        return requireNotNull(
            jdbc.queryForObject(
                """
                UPDATE event_cursor
                SET last_event_id = last_event_id + 1
                WHERE restaurant_id = :restaurantId
                RETURNING last_event_id
                """.trimIndent(),
                mapOf("restaurantId" to restaurantId),
                Long::class.java,
            ),
        )
    }

    private fun incrementVersion(event: DomainEvent): Long =
        requireNotNull(
            jdbc.queryForObject(
                """
                INSERT INTO event_aggregate_version (
                    restaurant_id, aggregate_type, aggregate_id, version
                )
                VALUES (:restaurantId, :aggregateType, :aggregateId, 1)
                ON CONFLICT (restaurant_id, aggregate_type, aggregate_id)
                DO UPDATE SET version = event_aggregate_version.version + 1
                RETURNING version
                """.trimIndent(),
                aggregateParameters(event),
                Long::class.java,
            ),
        )

    private fun advancePersistedVersion(event: DomainEvent, version: Long): Long {
        require(version > 0) { "Persisted aggregate version must be positive" }
        val advanced = jdbc.query(
            """
            INSERT INTO event_aggregate_version (
                restaurant_id, aggregate_type, aggregate_id, version
            )
            VALUES (:restaurantId, :aggregateType, :aggregateId, :version)
            ON CONFLICT (restaurant_id, aggregate_type, aggregate_id)
            DO UPDATE SET version = EXCLUDED.version
            WHERE event_aggregate_version.version < EXCLUDED.version
            RETURNING version
            """.trimIndent(),
            aggregateParameters(event) + ("version" to version),
        ) { result, _ -> result.getLong("version") }
        check(advanced.size == 1) { "Aggregate version must increase monotonically" }
        return advanced.single()
    }

    private fun aggregateParameters(event: DomainEvent): Map<String, Any> =
        mapOf(
            "restaurantId" to event.restaurantId,
            "aggregateType" to event.aggregateType.name,
            "aggregateId" to event.aggregateId,
        )
}
