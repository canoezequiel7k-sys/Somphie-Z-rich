package com.somphie.floor.api

import com.somphie.audit.AuditService
import com.somphie.events.DomainEvent
import com.somphie.events.DomainEventType
import com.somphie.events.EventAggregateType
import com.somphie.events.EventOutboxService
import com.somphie.events.EventVersion
import com.somphie.floor.application.ParticipantRevocationService
import com.somphie.floor.application.StaffSessionClosureService
import com.somphie.floor.application.StaffSessionQueryService
import com.somphie.floor.application.WaiterCallService
import com.somphie.floor.domain.JoinCode
import com.somphie.floor.domain.SessionCloseReason
import com.somphie.floor.domain.SessionInfoDto
import com.somphie.floor.domain.SessionParticipant
import com.somphie.floor.domain.SessionParticipantRole
import com.somphie.floor.domain.TableSession
import com.somphie.floor.domain.TableSessionStatus
import com.somphie.floor.infrastructure.JoinCodeGenerator
import com.somphie.identity.application.IdentityService
import com.somphie.identity.domain.StaffProfile
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.idempotency.IdempotencyService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ForcedSessionCloseRequest(val reason: String)

@RestController
class StaffFloorController(
    private val jdbc: NamedParameterJdbcTemplate,
    private val identityService: IdentityService,
    private val idempotencyService: IdempotencyService,
    private val auditService: AuditService,
    private val joinCodeGenerator: JoinCodeGenerator,
    private val participantRevocationService: ParticipantRevocationService,
    private val sessionClosureService: StaffSessionClosureService,
    private val staffSessionQueryService: StaffSessionQueryService,
    private val waiterCallService: WaiterCallService,
    private val clock: Clock,
    private val eventOutbox: EventOutboxService,
) {
    private data class StoredSessionState(
        val id: UUID,
        val tableId: UUID,
        val status: TableSessionStatus,
        val openedAt: Instant,
        val closedAt: Instant?,
        val closeReason: SessionCloseReason?,
        val joinCode: JoinCode?,
        val lastActivityAt: Instant,
    )

    @GetMapping("/staff/tables")
    fun listTables(@AuthenticationPrincipal jwt: Jwt): Map<String, Any> {
        val profile = authorize(jwt, "table.read")
        return staffSessionQueryService.getTables(profile.account.restaurantId)
    }

    @GetMapping("/staff/sessions/{sessionId}")
    fun getSession(
        @PathVariable sessionId: String,
        @AuthenticationPrincipal jwt: Jwt,
    ): Map<String, Any> {
        val profile = authorize(jwt, "session.read")
        return staffSessionQueryService.getSession(
            parseUuid(sessionId, "sessionId"),
            profile.account.restaurantId,
        )
    }

    @PostMapping("/staff/sessions/{sessionId}/close")
    fun closeSession(
        @PathVariable sessionId: String,
        @AuthenticationPrincipal jwt: Jwt,
    ): Map<String, Any> {
        val profile = authorize(jwt, "session.close")
        return sessionClosureService.closeNormally(
            parseUuid(sessionId, "sessionId"),
            profile.account.restaurantId,
            profile.account.id,
        )
    }

    @PostMapping("/staff/sessions/{sessionId}/force-close")
    @Transactional
    fun forceCloseSession(
        @PathVariable sessionId: String,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody request: ForcedSessionCloseRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Map<String, Any>> {
        val profile = authorize(jwt, "session.force_close")
        val sessionUuid = parseUuid(sessionId, "sessionId")
        validateIdempotencyKey(idempotencyKey)
        val scope = "staff-session-force-close:${profile.account.restaurantId}:${profile.account.id}:$sessionUuid"
        return idempotencyService.executeIdempotent(scope, idempotencyKey, request.toString()) {
            ResponseEntity.status(HttpStatus.OK).body(
                sessionClosureService.forceClose(
                    sessionUuid,
                    profile.account.restaurantId,
                    profile.account.id,
                    requireNotNull(idempotencyKey),
                    request.reason,
                ),
            )
        }
    }

    @GetMapping("/staff/waiter-calls")
    fun listWaiterCalls(
        @RequestParam(required = false) status: List<String>?,
        @AuthenticationPrincipal jwt: Jwt,
    ): Map<String, Any> {
        val profile = authorize(jwt, "waiter_call.handle")
        return waiterCallService.listForStaff(profile.account.restaurantId, status)
    }

    @PostMapping("/staff/waiter-calls/{waiterCallId}/acknowledge")
    fun acknowledgeWaiterCall(
        @PathVariable waiterCallId: String,
        @AuthenticationPrincipal jwt: Jwt,
    ): Map<String, Any> {
        val profile = authorize(jwt, "waiter_call.handle")
        return waiterCallService.acknowledge(
            parseUuid(waiterCallId, "waiterCallId"),
            profile.account.restaurantId,
        )
    }

    @PostMapping("/staff/waiter-calls/{waiterCallId}/resolve")
    fun resolveWaiterCall(
        @PathVariable waiterCallId: String,
        @AuthenticationPrincipal jwt: Jwt,
    ): Map<String, Any> {
        val profile = authorize(jwt, "waiter_call.handle")
        return waiterCallService.resolve(
            parseUuid(waiterCallId, "waiterCallId"),
            profile.account.restaurantId,
        )
    }

    @PostMapping("/staff/tables/{tableId}/sessions")
    @Transactional
    fun openSession(
        @PathVariable tableId: String,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Map<String, Any>> {
        val profile = authorize(jwt, "session.open_manual")
        val tableUuid = parseUuid(tableId)
        validateIdempotencyKey(idempotencyKey)
        val restaurantId = profile.account.restaurantId.toString()
        val userId = profile.account.id.toString()
        // Tomar el lock antes de leer la idempotencia serializa también reintentos simultáneos.
        val table = jdbc.query(
            """
            SELECT label, active FROM dining_table
            WHERE id = CAST(:tableId AS UUID) AND restaurant_id = CAST(:restaurantId AS UUID)
            FOR UPDATE
            """.trimIndent(),
            mapOf("tableId" to tableUuid.toString(), "restaurantId" to restaurantId),
        ) { rs, _ -> rs.getString("label") to rs.getBoolean("active") }.firstOrNull()
            ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Table not found")

        return idempotencyService.executeIdempotent(
            scope = "staff-session-open:$restaurantId:$userId:$tableUuid",
            idempotencyKey = idempotencyKey,
            requestBody = "{}",
        ) {
            if (!table.second) throw DomainException(ErrorCode.TABLE_UNAVAILABLE, "Table is inactive")
            val existing = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM table_session
                WHERE table_id = CAST(:tableId AS UUID) AND status = 'OPEN'
                """.trimIndent(),
                mapOf("tableId" to tableUuid.toString()),
                Int::class.java,
            ) ?: 0
            if (existing > 0) {
                throw DomainException(ErrorCode.SESSION_ALREADY_OPEN, "Table already has an open session")
            }

            val session = TableSession.openManually(
                UUID.randomUUID(),
                tableUuid,
                joinCodeGenerator.generate(),
                clock.instant(),
            )
            val joinCode = requireNotNull(session.joinCode)
            jdbc.update(
                """
                INSERT INTO table_session
                    (id, restaurant_id, table_id, status, join_code, opened_at, opened_by_type,
                     opened_by_user_id, last_activity_at)
                VALUES
                    (CAST(:id AS UUID), CAST(:restaurantId AS UUID), CAST(:tableId AS UUID),
                     'OPEN', :joinCode, :openedAt, 'STAFF', CAST(:userId AS UUID), :lastActivityAt)
                """.trimIndent(),
                mapOf(
                    "id" to session.id.toString(),
                    "restaurantId" to restaurantId,
                    "tableId" to tableUuid.toString(),
                    "joinCode" to joinCode.value,
                    "openedAt" to java.sql.Timestamp.from(session.openedAt),
                    "lastActivityAt" to java.sql.Timestamp.from(session.lastActivityAt),
                    "userId" to userId,
                ),
            )
            auditService.record(
                restaurantId = restaurantId,
                actorType = "USER",
                actorId = userId,
                action = "SESSION_OPENED",
                targetType = "SESSION",
                targetId = session.id.toString(),
                afterSummary = """{"openedBy":"STAFF","tableId":"$tableUuid"}""",
            )
            appendSessionAndTableUpdates(profile.account.restaurantId, session.id, tableUuid)

            ResponseEntity.status(HttpStatus.CREATED).body(
                mapOf(
                    "session" to SessionInfoDto(
                        id = session.id.toString(),
                        status = session.status.name,
                        tableLabel = table.first,
                        openedAt = session.openedAt,
                    ),
                    "joinCode" to joinCode.value,
                ),
            )
        }
    }

    @PostMapping("/staff/sessions/{sessionId}/join-code/rotate")
    @Transactional
    fun rotateJoinCode(
        @PathVariable sessionId: String,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Map<String, Any>> {
        val profile = authorize(jwt, "participant.revoke")
        val sessionUuid = parseUuid(sessionId)
        validateIdempotencyKey(idempotencyKey)
        val restaurantId = profile.account.restaurantId.toString()
        val userId = profile.account.id.toString()
        val session = jdbc.query(
            """
            SELECT id, table_id, status, opened_at, closed_at, close_reason, join_code, last_activity_at
            FROM table_session
            WHERE id = CAST(:sessionId AS UUID) AND restaurant_id = CAST(:restaurantId AS UUID)
            FOR UPDATE
            """.trimIndent(),
            mapOf("sessionId" to sessionUuid.toString(), "restaurantId" to restaurantId),
        ) { rs, _ ->
            StoredSessionState(
                id = rs.getObject("id", UUID::class.java),
                tableId = rs.getObject("table_id", UUID::class.java),
                status = TableSessionStatus.valueOf(rs.getString("status")),
                openedAt = rs.getTimestamp("opened_at").toInstant(),
                closedAt = rs.getTimestamp("closed_at")?.toInstant(),
                closeReason = rs.getString("close_reason")?.let(SessionCloseReason::valueOf),
                joinCode = rs.getString("join_code")?.let(JoinCode::of),
                lastActivityAt = rs.getTimestamp("last_activity_at").toInstant(),
            )
        }.firstOrNull() ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Session not found")

        return idempotencyService.executeIdempotent(
            scope = "staff-session-rotate:$restaurantId:$userId:$sessionUuid",
            idempotencyKey = idempotencyKey,
            requestBody = "{}",
        ) {
            val participants = jdbc.query(
                """
                SELECT id, display_name, role, user_id, revoked_at
                FROM session_participant WHERE session_id = CAST(:sessionId AS UUID)
                """.trimIndent(),
                mapOf("sessionId" to sessionUuid.toString()),
            ) { rs, _ ->
                SessionParticipant(
                    id = rs.getObject("id", UUID::class.java),
                    displayName = rs.getString("display_name"),
                    role = SessionParticipantRole.valueOf(rs.getString("role")),
                    userId = rs.getObject("user_id", UUID::class.java),
                    revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
                )
            }
            val aggregate = TableSession.fromStoredState(
                id = session.id,
                tableId = session.tableId,
                status = session.status,
                openedAt = session.openedAt,
                closedAt = session.closedAt,
                closeReason = session.closeReason,
                joinCode = session.joinCode,
                participants = participants,
                lastActivityAt = session.lastActivityAt,
            )
            val rotated = aggregate.rotateJoinCode(
                joinCodeGenerator.generate(excluding = session.joinCode),
                clock.instant(),
            )
            val newCode = requireNotNull(rotated.joinCode)
            jdbc.update(
                """
                UPDATE table_session SET join_code = :joinCode, last_activity_at = :lastActivityAt
                WHERE id = CAST(:sessionId AS UUID) AND restaurant_id = CAST(:restaurantId AS UUID)
                """.trimIndent(),
                mapOf(
                    "joinCode" to newCode.value,
                    "lastActivityAt" to java.sql.Timestamp.from(rotated.lastActivityAt),
                    "sessionId" to sessionUuid.toString(),
                    "restaurantId" to restaurantId,
                ),
            )
            eventOutbox.append(
                DomainEvent(
                    restaurantId = profile.account.restaurantId,
                    type = DomainEventType.SESSION_UPDATED,
                    aggregateType = EventAggregateType.SESSION,
                    aggregateId = sessionUuid,
                    version = EventVersion.Increment,
                    sessionId = sessionUuid,
                ),
            )
            // El catálogo vigente excluye la rotación del joinCode de las acciones auditadas.
            ResponseEntity.ok(mapOf("joinCode" to newCode.value))
        }
    }

    @PostMapping("/staff/sessions/{sessionId}/participants/{participantId}/revoke")
    fun revokeParticipant(
        @PathVariable sessionId: String,
        @PathVariable participantId: String,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Map<String, Any>> {
        val profile = authorize(jwt, "participant.revoke")
        val sessionUuid = parseUuid(sessionId)
        val participantUuid = parseUuid(participantId)
        return ResponseEntity.ok(
            participantRevocationService.revokeForStaff(
                restaurantId = profile.account.restaurantId,
                userId = profile.account.id,
                sessionId = sessionUuid,
                targetParticipantId = participantUuid,
            ),
        )
    }

    private fun authorize(jwt: Jwt, permission: String): StaffProfile {
        val profile = identityService.me(jwt.subject, jwt.getClaim("rid"), jwt.getClaim("tv"))
        if (permission !in profile.permissions) {
            throw DomainException(ErrorCode.FORBIDDEN, "Staff permission is required")
        }
        return profile
    }

    private fun parseUuid(value: String, field: String = "tableId"): UUID = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid $field")
    }

    private fun validateIdempotencyKey(value: String?) {
        if (value.isNullOrBlank()) return
        val parsed = try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key must be a UUID v4")
        }
        if (parsed.version() != 4 || parsed.toString() != value) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key must be a UUID v4")
        }
    }

    private fun appendSessionAndTableUpdates(restaurantId: UUID, sessionId: UUID, tableId: UUID) {
        eventOutbox.append(
            DomainEvent(
                restaurantId,
                DomainEventType.SESSION_UPDATED,
                EventAggregateType.SESSION,
                sessionId,
                EventVersion.Increment,
                sessionId,
            ),
        )
        eventOutbox.append(
            DomainEvent(
                restaurantId,
                DomainEventType.TABLE_UPDATED,
                EventAggregateType.TABLE,
                tableId,
                EventVersion.Increment,
                sessionId,
            ),
        )
    }
}
