package com.somphie.floor.api

import com.somphie.floor.domain.JoinCode
import com.somphie.floor.domain.OpenSessionRequest
import com.somphie.floor.domain.JoinSessionRequest
import com.somphie.floor.domain.ParticipantInfoDto
import com.somphie.floor.domain.PublicTable
import com.somphie.floor.domain.SessionAccessResponse
import com.somphie.floor.domain.SessionInfoDto
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.security.SecureRandom
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

@RestController
class FloorController(
    private val jdbc: NamedParameterJdbcTemplate
) {

    private val secureRandom = SecureRandom()
    private val allowedJoinChars = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    @GetMapping("/public/tables/{tableCode}")
    fun getPublicTable(@PathVariable tableCode: String): ResponseEntity<PublicTable> {
        val rows = jdbc.query(
            "SELECT dt.id AS table_id, dt.label, dt.restaurant_id, dt.active, ts.id AS session_id, ts.status AS session_status FROM dining_table dt LEFT JOIN table_session ts ON dt.id = ts.table_id AND ts.status = 'OPEN' WHERE dt.table_code = :tableCode",
            mapOf("tableCode" to tableCode)
        ) { rs, _ ->
            val active = rs.getBoolean("active")
            val sessionId = rs.getString("session_id")
            val tableLabel = rs.getString("label")
            val restaurantId = rs.getString("restaurant_id")

            PublicTable(
                tableLabel = tableLabel,
                restaurantId = restaurantId,
                restaurantOpen = active,
                hasActiveSession = sessionId != null,
                canOpen = active && sessionId == null
            )
        }

        val table = rows.firstOrNull() ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Table not found")
        return ResponseEntity.ok(table)
    }

    @PostMapping("/public/tables/{tableCode}/sessions")
    fun openSession(
        @PathVariable tableCode: String,
        @Valid @RequestBody request: OpenSessionRequest
    ): ResponseEntity<SessionAccessResponse> {
        val tableSql = "SELECT id, restaurant_id, label, active FROM dining_table WHERE table_code = :tableCode"
        val tables = jdbc.query(tableSql, mapOf("tableCode" to tableCode)) { rs, _ ->
            mapOf(
                "id" to rs.getString("id"),
                "restaurant_id" to rs.getString("restaurant_id"),
                "label" to rs.getString("label"),
                "active" to rs.getBoolean("active")
            )
        }
        val table = tables.firstOrNull() ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Table not found")
        if (table["active"] != true) {
            throw DomainException(ErrorCode.SESSION_CLOSED, "Table is not available")
        }

        val tableId = table["id"] as String
        val restaurantId = table["restaurant_id"] as String
        val tableLabel = table["label"] as String

        val existingSession = jdbc.queryForObject(
            "SELECT COUNT(1) FROM table_session WHERE table_id = CAST(:tableId AS UUID) AND status = 'OPEN'",
            mapOf("tableId" to tableId),
            Int::class.java
        ) ?: 0

        if (existingSession > 0) {
            throw DomainException(ErrorCode.SESSION_FULL, "Session already open for this table")
        }

        val sessionId = UUID.randomUUID().toString()
        val participantId = UUID.randomUUID().toString()
        val joinCode = generateJoinCode()
        val guestToken = generateGuestToken()
        val tokenHash = hashToken(guestToken)
        val now = Instant.now()

        val insertSessionSql = """
            INSERT INTO table_session (id, restaurant_id, table_id, status, join_code, opened_at, opened_by_type, last_activity_at)
            VALUES (CAST(:sessionId AS UUID), CAST(:restaurantId AS UUID), CAST(:tableId AS UUID), 'OPEN', :joinCode, :openedAt, 'CUSTOMER', :lastActivityAt)
        """
        jdbc.update(insertSessionSql, mapOf(
            "sessionId" to sessionId,
            "restaurantId" to restaurantId,
            "tableId" to tableId,
            "joinCode" to joinCode,
            "openedAt" to java.sql.Timestamp.from(now),
            "lastActivityAt" to java.sql.Timestamp.from(now)
        ))

        val insertParticipantSql = """
            INSERT INTO session_participant (id, session_id, display_name, role, token_hash, joined_at)
            VALUES (CAST(:participantId AS UUID), CAST(:sessionId AS UUID), :displayName, 'HOST', :tokenHash, :joinedAt)
        """
        jdbc.update(insertParticipantSql, mapOf(
            "participantId" to participantId,
            "sessionId" to sessionId,
            "displayName" to request.displayName,
            "tokenHash" to tokenHash,
            "joinedAt" to java.sql.Timestamp.from(now)
        ))

        val response = SessionAccessResponse(
            session = SessionInfoDto(
                id = sessionId,
                status = "OPEN",
                tableLabel = tableLabel,
                openedAt = now
            ),
            participant = ParticipantInfoDto(
                id = participantId,
                displayName = request.displayName,
                role = "HOST"
            ),
            guestToken = guestToken,
            joinCode = joinCode
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @PostMapping("/public/tables/{tableCode}/join")
    fun joinSession(
        @PathVariable tableCode: String,
        @Valid @RequestBody request: JoinSessionRequest
    ): ResponseEntity<SessionAccessResponse> {
        val sessions = jdbc.query(
            "SELECT ts.id AS session_id, ts.join_code, ts.status, dt.label AS table_label FROM dining_table dt JOIN table_session ts ON dt.id = ts.table_id AND ts.status = 'OPEN' WHERE dt.table_code = :tableCode",
            mapOf("tableCode" to tableCode)
        ) { rs, _ ->
            mapOf(
                "session_id" to rs.getString("session_id"),
                "join_code" to rs.getString("join_code"),
                "status" to rs.getString("status"),
                "table_label" to rs.getString("table_label")
            )
        }

        val sessionData = sessions.firstOrNull() ?: throw DomainException(ErrorCode.SESSION_CLOSED, "No active session for this table")

        if (sessionData["join_code"] != request.joinCode) {
            throw DomainException(ErrorCode.FORBIDDEN, "Invalid join code")
        }

        val sessionId = sessionData["session_id"] as String
        val tableLabel = sessionData["table_label"] as String

        val hostCount = jdbc.queryForObject(
            "SELECT COUNT(1) FROM session_participant WHERE session_id = CAST(:sessionId AS UUID) AND role = 'HOST' AND revoked_at IS NULL",
            mapOf("sessionId" to sessionId),
            Int::class.java
        ) ?: 0

        val role = if (hostCount == 0) "HOST" else "MEMBER"

        val participantId = UUID.randomUUID().toString()
        val guestToken = generateGuestToken()
        val tokenHash = hashToken(guestToken)
        val now = Instant.now()

        val insertParticipantSql = """
            INSERT INTO session_participant (id, session_id, display_name, role, token_hash, joined_at)
            VALUES (CAST(:participantId AS UUID), CAST(:sessionId AS UUID), :displayName, :role, :tokenHash, :joinedAt)
        """
        jdbc.update(insertParticipantSql, mapOf(
            "participantId" to participantId,
            "sessionId" to sessionId,
            "displayName" to request.displayName,
            "role" to role,
            "tokenHash" to tokenHash,
            "joinedAt" to java.sql.Timestamp.from(now)
        ))

        val response = SessionAccessResponse(
            session = SessionInfoDto(
                id = sessionId,
                status = "OPEN",
                tableLabel = tableLabel,
                openedAt = now
            ),
            participant = ParticipantInfoDto(
                id = participantId,
                displayName = request.displayName,
                role = role
            ),
            guestToken = guestToken
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    private fun generateJoinCode(): String {
        val sb = StringBuilder(6)
        for (i in 0 until 6) {
            sb.append(allowedJoinChars[secureRandom.nextInt(allowedJoinChars.length)])
        }
        return JoinCode.of(sb.toString()).value
    }

    private fun generateGuestToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return "gt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hashToken(token: String): String {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
    }
}
