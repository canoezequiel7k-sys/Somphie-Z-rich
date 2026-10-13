package com.somphie.floor.application

import com.somphie.audit.AuditService
import com.somphie.floor.domain.JoinCode
import com.somphie.floor.domain.SessionCloseReason
import com.somphie.floor.domain.SessionParticipant
import com.somphie.floor.domain.SessionParticipantRole
import com.somphie.floor.domain.TableSession
import com.somphie.floor.domain.TableSessionStatus
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

@Service
class ParticipantRevocationService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val auditService: AuditService,
    private val clock: Clock,
) {
    private data class SessionState(
        val id: UUID,
        val tableId: UUID,
        val status: TableSessionStatus,
        val openedAt: Instant,
        val closedAt: Instant?,
        val closeReason: SessionCloseReason?,
        val joinCode: JoinCode?,
        val lastActivityAt: Instant,
    )

    @Transactional
    fun revokeForStaff(
        restaurantId: UUID,
        userId: UUID,
        sessionId: UUID,
        targetParticipantId: UUID,
    ): Map<String, Any> = revoke(
        restaurantId = restaurantId,
        sessionId = sessionId,
        targetParticipantId = targetParticipantId,
        actorParticipantId = null,
        actorId = userId,
        actorType = "USER",
    )

    @Transactional
    fun revokeForHost(authorization: String?, targetParticipantId: UUID): Map<String, Any> {
        val token = authorization
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.takeIf(String::isNotBlank)
            ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "Guest token is required")
        val tokenHash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
        )
        val actor = jdbc.query(
            """
            SELECT sp.session_id, ts.restaurant_id, sp.id
            FROM session_participant sp
            JOIN table_session ts ON ts.id = sp.session_id
            WHERE sp.token_hash = :tokenHash
            """.trimIndent(),
            mapOf("tokenHash" to tokenHash),
        ) { rs, _ ->
            Triple(
                rs.getObject("restaurant_id", UUID::class.java),
                rs.getObject("session_id", UUID::class.java),
                rs.getObject("id", UUID::class.java),
            )
        }.firstOrNull() ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "Invalid guest token")

        return revoke(
            restaurantId = actor.first,
            sessionId = actor.second,
            targetParticipantId = targetParticipantId,
            actorParticipantId = actor.third,
            actorId = actor.third,
            actorType = "PARTICIPANT",
        )
    }

    private fun revoke(
        restaurantId: UUID,
        sessionId: UUID,
        targetParticipantId: UUID,
        actorParticipantId: UUID?,
        actorId: UUID,
        actorType: String,
    ): Map<String, Any> {
        // Ambas rutas bloquean primero la sesión y después sus participantes para serializar cambios del agregado.
        val session = jdbc.query(
            """
            SELECT id, table_id, status, opened_at, closed_at, close_reason, join_code, last_activity_at
            FROM table_session
            WHERE id = :sessionId AND restaurant_id = :restaurantId
            FOR UPDATE
            """.trimIndent(),
            mapOf("sessionId" to sessionId, "restaurantId" to restaurantId),
        ) { rs, _ ->
            SessionState(
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
        val participants = jdbc.query(
            """
            SELECT id, display_name, role, user_id, revoked_at
            FROM session_participant WHERE session_id = :sessionId
            ORDER BY id FOR UPDATE
            """.trimIndent(),
            mapOf("sessionId" to sessionId),
        ) { rs, _ ->
            SessionParticipant(
                id = rs.getObject("id", UUID::class.java),
                displayName = rs.getString("display_name"),
                role = SessionParticipantRole.valueOf(rs.getString("role")),
                userId = rs.getObject("user_id", UUID::class.java),
                revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
            )
        }
        if (actorParticipantId != null) {
            val host = participants.firstOrNull { it.id == actorParticipantId }
            if (host == null || host.revokedAt != null) {
                throw DomainException(ErrorCode.SESSION_ACCESS_REVOKED, "Guest access was revoked")
            }
            if (host.role != SessionParticipantRole.HOST) {
                throw DomainException(ErrorCode.FORBIDDEN, "Only the host can revoke participants")
            }
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
        val now = clock.instant()
        val updated = aggregate.revokeParticipant(actorParticipantId, targetParticipantId, now)
        val targetBefore = participants.first { it.id == targetParticipantId }
        val target = updated.participants.first { it.id == targetParticipantId }
        if (targetBefore.revokedAt == null) {
            jdbc.update(
                """
                UPDATE session_participant SET revoked_at = :revokedAt
                WHERE id = :participantId AND session_id = :sessionId AND revoked_at IS NULL
                """.trimIndent(),
                mapOf(
                    "revokedAt" to java.sql.Timestamp.from(now),
                    "participantId" to targetParticipantId,
                    "sessionId" to sessionId,
                ),
            )
            jdbc.update(
                """
                UPDATE table_session SET last_activity_at = :lastActivityAt
                WHERE id = :sessionId AND restaurant_id = :restaurantId
                """.trimIndent(),
                mapOf(
                    "lastActivityAt" to java.sql.Timestamp.from(updated.lastActivityAt),
                    "sessionId" to sessionId,
                    "restaurantId" to restaurantId,
                ),
            )
            auditService.record(
                restaurantId = restaurantId.toString(),
                actorType = actorType,
                actorId = actorId.toString(),
                action = "PARTICIPANT_REVOKED",
                targetType = "PARTICIPANT",
                targetId = targetParticipantId.toString(),
            )
        }
        return mapOf(
            "id" to target.id.toString(),
            "displayName" to target.displayName,
            "role" to target.role.name,
            "revoked" to (target.revokedAt != null),
        )
    }
}
