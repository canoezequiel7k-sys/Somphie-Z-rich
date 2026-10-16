package com.somphie.events

import com.somphie.identity.application.IdentityService
import com.somphie.identity.domain.StaffReadScope
import com.somphie.identity.domain.StaffProfile
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

data class EventStreamAccess(
    val restaurantId: UUID,
    val sessionId: UUID? = null,
    val staff: StaffProfile? = null,
)

@Service
class EventStreamService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val identityService: IdentityService,
    private val clock: Clock,
) {
    fun authorize(scope: String, authorization: String?, jwt: Jwt?): EventStreamAccess =
        when (scope) {
            "session" -> authorizeGuest(authorization)
            "staff" -> authorizeStaff(jwt)
            else -> throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid event stream scope")
        }

    fun ensureCursor(access: EventStreamAccess, cursor: Long) {
        val latestCursor = jdbc.queryForObject(
            """
            SELECT COALESCE(
                (SELECT last_event_id FROM event_cursor WHERE restaurant_id = :restaurantId),
                0
            )
            """.trimIndent(),
            mapOf("restaurantId" to access.restaurantId),
            Long::class.java,
        ) ?: 0L
        if (cursor > latestCursor) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Event cursor is ahead of the restaurant stream")
        }
        val lastDeleted = jdbc.queryForObject(
            """
            SELECT COALESCE(
                (SELECT last_deleted_event_id FROM event_retention WHERE restaurant_id = :restaurantId),
                0
            )
            """.trimIndent(),
            mapOf("restaurantId" to access.restaurantId),
            Long::class.java,
        ) ?: 0L
        val cursorTime = if (cursor > 0) {
            jdbc.query(
                """
                SELECT occurred_at FROM event_log
                WHERE restaurant_id = :restaurantId AND id = :cursor
                """.trimIndent(),
                mapOf("restaurantId" to access.restaurantId, "cursor" to cursor),
            ) { rs, _ -> rs.getTimestamp("occurred_at").toInstant() }.firstOrNull()
        } else {
            null
        }
        if (cursor < lastDeleted || cursorTime?.isBefore(clock.instant().minus(RETENTION)) == true) {
            throw DomainException(ErrorCode.EVENT_ID_TOO_OLD, "Event cursor is outside retention")
        }
    }

    fun eventsAfter(access: EventStreamAccess, cursor: Long): List<StoredDomainEvent> {
        val staff = access.staff
        val parameters = mutableMapOf<String, Any>(
            "restaurantId" to access.restaurantId,
            "cursor" to cursor,
            "limit" to PAGE_SIZE,
        )
        val visibility = if (access.sessionId != null) {
            parameters["sessionId"] = access.sessionId
            "(e.session_id = :sessionId OR e.aggregate_type = 'CATALOG')"
        } else {
            val types = staffEventTypes(staff!!)
            if (types.isEmpty()) {
                throw DomainException(ErrorCode.STREAM_SCOPE_FORBIDDEN, "Staff account cannot read event streams")
            }
            parameters["types"] = types
            var filter = "e.type IN (:types)"
            val stationScoped = StaffReadScope.isKitchenOnly(staff.roles)
            val stationIds = if (stationScoped) {
                staff.stations.map { it.id }
            } else {
                emptyList()
            }
            if (stationScoped) {
                if (stationIds.isEmpty()) {
                    filter += " AND e.aggregate_type = 'CATALOG'"
                } else {
                    parameters["stationIds"] = stationIds
                    filter += """
                        AND (
                            e.station_id IN (:stationIds)
                            OR (
                                e.station_id IS NULL
                                AND (
                                    e.aggregate_type = 'CATALOG'
                                    OR (
                                        e.aggregate_type = 'ORDER'
                                        AND EXISTS (
                                        SELECT 1 FROM order_item oi
                                        WHERE oi.order_id = e.aggregate_id
                                          AND oi.station_id IN (:stationIds)
                                          AND oi.status IN ('QUEUED', 'IN_PREPARATION', 'READY', 'DELIVERED')
                                        )
                                    )
                                )
                            )
                        )
                    """.trimIndent()
                }
            }
            filter
        }
        return jdbc.query(
            """
            SELECT e.id, e.type, e.aggregate_type, e.aggregate_id, e.version,
                   e.session_id, e.station_id, e.occurred_at
            FROM event_log e
            WHERE e.restaurant_id = :restaurantId
              AND e.id > :cursor
              AND $visibility
            ORDER BY e.id
            LIMIT :limit
            """.trimIndent(),
            parameters,
        ) { rs, _ ->
            StoredDomainEvent(
                eventId = rs.getLong("id"),
                type = DomainEventType.entries.first { it.wireValue == rs.getString("type") },
                aggregateType = EventAggregateType.valueOf(rs.getString("aggregate_type")),
                aggregateId = rs.getObject("aggregate_id", UUID::class.java),
                version = rs.getLong("version"),
                sessionId = rs.getObject("session_id", UUID::class.java),
                stationId = rs.getObject("station_id", UUID::class.java),
                occurredAt = rs.getTimestamp("occurred_at").toInstant(),
            )
        }
    }

    private fun authorizeGuest(authorization: String?): EventStreamAccess {
        val token = authorization?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.takeIf { it.startsWith(GUEST_TOKEN_PREFIX) }
            ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "Guest token is required")
        val tokenHash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
        )
        val guest = jdbc.query(
            """
            SELECT ts.restaurant_id, ts.id AS session_id, ts.status, ts.closed_at, sp.revoked_at
            FROM table_session ts
            JOIN session_participant sp ON sp.session_id = ts.id
            WHERE sp.token_hash = :tokenHash
            """.trimIndent(),
            mapOf("tokenHash" to tokenHash),
        ) { rs, _ ->
            GuestStreamIdentity(
                access = EventStreamAccess(
                    restaurantId = rs.getObject("restaurant_id", UUID::class.java),
                    sessionId = rs.getObject("session_id", UUID::class.java),
                ),
                closed = rs.getString("status") == "CLOSED",
                closedAt = rs.getTimestamp("closed_at")?.toInstant(),
                revoked = rs.getTimestamp("revoked_at") != null,
            )
        }.firstOrNull() ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "Invalid guest token")
        if (guest.revoked) throw DomainException(ErrorCode.SESSION_ACCESS_REVOKED, "Guest stream access was revoked")
        if (guest.closed &&
            (guest.closedAt == null || guest.closedAt.isBefore(clock.instant().minus(CLOSED_READ_WINDOW)))
        ) {
            throw DomainException(ErrorCode.SESSION_CLOSED, "Guest stream session is no longer available")
        }
        return guest.access
    }

    private fun authorizeStaff(jwt: Jwt?): EventStreamAccess {
        val token = jwt ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "Staff JWT is required")
        val profile = identityService.me(token.subject, token.claims["rid"], token.claims["tv"])
        if (staffEventTypes(profile).isEmpty()) {
            throw DomainException(ErrorCode.STREAM_SCOPE_FORBIDDEN, "Staff account cannot read event streams")
        }
        return EventStreamAccess(profile.account.restaurantId, staff = profile)
    }

    private fun staffEventTypes(profile: StaffProfile): List<String> = buildList {
        val permissions = profile.permissions.toSet()
        if ("order.read" in permissions) addAll(listOf("order.created", "order.updated"))
        if (permissions.any { it in setOf("item.prepare", "item.deliver") }) add("item.updated")
        if ("session.read" in permissions) {
            addAll(listOf("session.updated", "session.closed", "bill.updated", "table.updated", "waiter.updated"))
        } else if ("table.read" in permissions) {
            add("table.updated")
        }
        if ("waiter_call.handle" in permissions) add("waiter.called")
        if (permissions.any { it in setOf("menu.read", "catalog.manage") }) add("catalog.updated")
    }.distinct()

    private data class GuestStreamIdentity(
        val access: EventStreamAccess,
        val closed: Boolean,
        val closedAt: java.time.Instant?,
        val revoked: Boolean,
    )

    private companion object {
        const val GUEST_TOKEN_PREFIX = "gt_"
        const val PAGE_SIZE = 100
        val CLOSED_READ_WINDOW: Duration = Duration.ofHours(24)
        val RETENTION: Duration = Duration.ofHours(48)
    }
}
