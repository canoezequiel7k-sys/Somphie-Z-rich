package com.somphie.identity.infrastructure

import com.somphie.audit.AuditService
import com.somphie.identity.application.IdentityRepository
import com.somphie.identity.domain.RefreshRotation
import com.somphie.identity.domain.StaffAccount
import com.somphie.identity.domain.StaffProfile
import com.somphie.identity.domain.StaffStation
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcIdentityRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val auditService: AuditService,
) : IdentityRepository {
    override fun findByEmail(email: String): StaffProfile? =
        jdbc.query(
            "SELECT id FROM app_user WHERE lower(email) = :email",
            mapOf("email" to email),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }
            .firstOrNull()
            ?.let(::loadProfile)

    override fun findById(id: UUID): StaffProfile? = loadProfile(id)

    override fun lockedUntil(attemptKey: String, now: Instant): Instant? =
        jdbc.query(
            "SELECT locked_until FROM auth_login_attempt WHERE attempt_key = :key AND locked_until > :now",
            mapOf("key" to attemptKey, "now" to timestamp(now)),
        ) { rs, _ -> rs.getTimestamp("locked_until").toInstant() }
            .firstOrNull()

    @Transactional
    override fun recordFailedLogin(attemptKey: String, now: Instant, knownProfile: StaffProfile?): Int {
        val failures = jdbc.queryForObject(
            """
            INSERT INTO auth_login_attempt (attempt_key, window_started_at, failure_count)
            VALUES (:key, :now, 1)
            ON CONFLICT (attempt_key) DO UPDATE SET
                window_started_at = CASE
                    WHEN auth_login_attempt.window_started_at <= :windowStart THEN :now
                    ELSE auth_login_attempt.window_started_at
                END,
                failure_count = CASE
                    WHEN auth_login_attempt.window_started_at <= :windowStart THEN 1
                    ELSE auth_login_attempt.failure_count + 1
                END,
                locked_until = CASE
                    WHEN auth_login_attempt.window_started_at <= :windowStart THEN NULL
                    ELSE auth_login_attempt.locked_until
                END
            RETURNING failure_count
            """.trimIndent(),
            mapOf(
                "key" to attemptKey,
                "now" to timestamp(now),
                "windowStart" to timestamp(now.minusSeconds(LOGIN_WINDOW_SECONDS)),
            ),
            Int::class.java,
        ) ?: 0

        if (failures >= MAX_LOGIN_FAILURES) {
            jdbc.update(
                "UPDATE auth_login_attempt SET locked_until = :until WHERE attempt_key = :key",
                mapOf("key" to attemptKey, "until" to timestamp(now.plusSeconds(LOGIN_LOCK_SECONDS))),
            )
            if (failures == MAX_LOGIN_FAILURES && knownProfile != null) {
                auditService.record(
                    restaurantId = knownProfile.account.restaurantId.toString(),
                    actorType = "STAFF",
                    actorId = knownProfile.account.id.toString(),
                    action = "identity.login_failed_locked",
                    targetType = "APP_USER",
                    targetId = knownProfile.account.id.toString(),
                )
            }
        }
        return failures
    }

    override fun clearFailedLogins(attemptKey: String) {
        jdbc.update("DELETE FROM auth_login_attempt WHERE attempt_key = :key", mapOf("key" to attemptKey))
    }

    override fun storeRefreshToken(
        userId: UUID,
        familyId: UUID,
        tokenHash: String,
        expiresAt: Instant,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO refresh_token (id, user_id, family_id, token_hash, expires_at, created_at)
            VALUES (:id, :userId, :familyId, :tokenHash, :expiresAt, :now)
            """.trimIndent(),
            mapOf(
                "id" to UUID.randomUUID(),
                "userId" to userId,
                "familyId" to familyId,
                "tokenHash" to tokenHash,
                "expiresAt" to timestamp(expiresAt),
                "now" to timestamp(now),
            ),
        )
    }

    @Transactional
    override fun rotateRefreshToken(
        tokenHash: String,
        replacementHash: String,
        now: Instant,
        expiresAt: Instant,
    ): RefreshRotation {
        val token = jdbc.query(
            """
            SELECT id, user_id, family_id, expires_at, used_at, revoked_at
            FROM refresh_token WHERE token_hash = :tokenHash FOR UPDATE
            """.trimIndent(),
            mapOf("tokenHash" to tokenHash),
        ) { rs, _ ->
            RefreshRecord(
                id = rs.getObject("id", UUID::class.java),
                userId = rs.getObject("user_id", UUID::class.java),
                familyId = rs.getObject("family_id", UUID::class.java),
                expiresAt = rs.getTimestamp("expires_at").toInstant(),
                usedAt = rs.getTimestamp("used_at")?.toInstant(),
                revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
            )
        }.firstOrNull() ?: return RefreshRotation.Invalid

        if (token.usedAt != null) {
            revokeFamily(token.familyId, now)
            return RefreshRotation.Reused
        }
        if (token.revokedAt != null || token.expiresAt <= now) {
            return RefreshRotation.Invalid
        }

        val profile = loadProfile(token.userId)
            ?.takeIf { it.account.status == ACTIVE_STATUS }
            ?: run {
                revokeFamily(token.familyId, now)
                return RefreshRotation.Invalid
            }

        val updated = jdbc.update(
            """
            UPDATE refresh_token SET used_at = :now
            WHERE id = :id AND used_at IS NULL AND revoked_at IS NULL
            """.trimIndent(),
            mapOf("id" to token.id, "now" to timestamp(now)),
        )
        if (updated != 1) {
            revokeFamily(token.familyId, now)
            return RefreshRotation.Reused
        }
        jdbc.update(
            """
            INSERT INTO refresh_token (id, user_id, family_id, token_hash, expires_at, created_at)
            VALUES (:id, :userId, :familyId, :tokenHash, :expiresAt, :now)
            """.trimIndent(),
            mapOf(
                "id" to UUID.randomUUID(),
                "userId" to token.userId,
                "familyId" to token.familyId,
                "tokenHash" to replacementHash,
                "expiresAt" to timestamp(expiresAt),
                "now" to timestamp(now),
            ),
        )
        return RefreshRotation.Rotated(profile)
    }

    override fun revokeRefreshToken(userId: UUID, tokenHash: String, now: Instant) {
        jdbc.update(
            """
            UPDATE refresh_token SET revoked_at = :now
            WHERE user_id = :userId AND token_hash = :tokenHash AND revoked_at IS NULL
            """.trimIndent(),
            mapOf("userId" to userId, "tokenHash" to tokenHash, "now" to timestamp(now)),
        )
    }

    @Transactional
    override fun changePassword(userId: UUID, passwordHash: String, now: Instant) {
        jdbc.update(
            """
            UPDATE app_user
            SET password_hash = :passwordHash, must_change_password = FALSE, token_version = token_version + 1
            WHERE id = :userId
            """.trimIndent(),
            mapOf("userId" to userId, "passwordHash" to passwordHash),
        )
        jdbc.update(
            "UPDATE refresh_token SET revoked_at = :now WHERE user_id = :userId AND revoked_at IS NULL",
            mapOf("userId" to userId, "now" to timestamp(now)),
        )
        val profile = loadProfile(userId) ?: return
        auditService.record(
            restaurantId = profile.account.restaurantId.toString(),
            actorType = "STAFF",
            actorId = userId.toString(),
            action = "identity.password_changed",
            targetType = "APP_USER",
            targetId = userId.toString(),
        )
    }

    @Transactional
    override fun seedDemoUsers(passwordHash: String) {
        val restaurantId = UUID.fromString(DEFAULT_RESTAURANT_ID)
        DEMO_USERS.forEach { demo ->
            jdbc.update(
                """
                INSERT INTO app_user (id, restaurant_id, email, password_hash, display_name, status)
                VALUES (:id, :restaurantId, :email, :passwordHash, :displayName, 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """.trimIndent(),
                mapOf(
                    "id" to UUID.fromString(demo.id),
                    "restaurantId" to restaurantId,
                    "email" to demo.email,
                    "passwordHash" to passwordHash,
                    "displayName" to demo.displayName,
                ),
            )
            jdbc.update(
                """
                INSERT INTO user_role (user_id, role_code)
                VALUES (:userId, :roleCode) ON CONFLICT DO NOTHING
                """.trimIndent(),
                mapOf("userId" to UUID.fromString(demo.id), "roleCode" to demo.role),
            )
            demo.stationCodes.forEach { stationCode ->
                jdbc.update(
                    """
                    INSERT INTO user_station (user_id, station_id)
                    SELECT :userId, id FROM station
                    WHERE restaurant_id = :restaurantId AND code = :stationCode
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                    mapOf(
                        "userId" to UUID.fromString(demo.id),
                        "restaurantId" to restaurantId,
                        "stationCode" to stationCode,
                    ),
                )
            }
        }
    }

    private fun loadProfile(id: UUID): StaffProfile? {
        val account = jdbc.query(
            """
            SELECT id, restaurant_id, email, password_hash, display_name, status,
                   token_version, must_change_password
            FROM app_user WHERE id = :id
            """.trimIndent(),
            mapOf("id" to id),
        ) { rs, _ ->
            StaffAccount(
                id = rs.getObject("id", UUID::class.java),
                restaurantId = rs.getObject("restaurant_id", UUID::class.java),
                email = rs.getString("email"),
                passwordHash = rs.getString("password_hash"),
                displayName = rs.getString("display_name"),
                status = rs.getString("status"),
                tokenVersion = rs.getLong("token_version"),
                mustChangePassword = rs.getBoolean("must_change_password"),
            )
        }.firstOrNull() ?: return null

        val roles = jdbc.query(
            "SELECT role_code FROM user_role WHERE user_id = :id ORDER BY role_code",
            mapOf("id" to id),
        ) { rs, _ -> rs.getString("role_code") }
        val permissions = jdbc.query(
            """
            SELECT DISTINCT rp.permission_code
            FROM user_role ur JOIN role_permission rp ON rp.role_code = ur.role_code
            WHERE ur.user_id = :id ORDER BY rp.permission_code
            """.trimIndent(),
            mapOf("id" to id),
        ) { rs, _ -> rs.getString("permission_code") }
        val stations = jdbc.query(
            """
            SELECT s.id, s.code, s.name FROM user_station us
            JOIN station s ON s.id = us.station_id
            WHERE us.user_id = :id AND s.restaurant_id = :restaurantId
            ORDER BY s.code
            """.trimIndent(),
            mapOf("id" to id, "restaurantId" to account.restaurantId),
        ) { rs, _ ->
            StaffStation(
                id = rs.getObject("id", UUID::class.java),
                code = rs.getString("code"),
                name = rs.getString("name"),
            )
        }
        return StaffProfile(account, roles, permissions, stations)
    }

    private fun revokeFamily(familyId: UUID, now: Instant) {
        jdbc.update(
            "UPDATE refresh_token SET revoked_at = COALESCE(revoked_at, :now) WHERE family_id = :familyId",
            mapOf("familyId" to familyId, "now" to timestamp(now)),
        )
    }

    private fun timestamp(instant: Instant): Timestamp = Timestamp.from(instant)

    private data class RefreshRecord(
        val id: UUID,
        val userId: UUID,
        val familyId: UUID,
        val expiresAt: Instant,
        val usedAt: Instant?,
        val revokedAt: Instant?,
    )

    private data class DemoUser(
        val id: String,
        val email: String,
        val displayName: String,
        val role: String,
        val stationCodes: List<String>,
    )

    private companion object {
        const val ACTIVE_STATUS = "ACTIVE"
        const val DEFAULT_RESTAURANT_ID = "6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68"
        const val MAX_LOGIN_FAILURES = 5
        const val LOGIN_WINDOW_SECONDS = 15 * 60L
        const val LOGIN_LOCK_SECONDS = 15 * 60L
        val DEMO_USERS = listOf(
            DemoUser("e6b8be16-51c5-4835-99f1-10d2259ee001", "owner@somphie.demo", "Somphie Owner", "OWNER", listOf("BAR", "KITCHEN")),
            DemoUser("e6b8be16-51c5-4835-99f1-10d2259ee002", "waiter@somphie.demo", "Somphie Waiter", "WAITER", emptyList()),
            DemoUser("e6b8be16-51c5-4835-99f1-10d2259ee003", "bar@somphie.demo", "Somphie Bar", "BAR", listOf("BAR")),
            DemoUser("e6b8be16-51c5-4835-99f1-10d2259ee004", "kitchen@somphie.demo", "Somphie Kitchen", "KITCHEN", listOf("KITCHEN")),
        )
    }
}
