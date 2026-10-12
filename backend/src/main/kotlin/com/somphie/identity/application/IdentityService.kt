package com.somphie.identity.application

import com.somphie.identity.domain.RefreshRotation
import com.somphie.identity.domain.StaffAuthResult
import com.somphie.identity.domain.StaffProfile
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID

@Service
class IdentityService(
    private val repository: IdentityRepository,
    private val crypto: IdentityCrypto,
    private val clock: Clock,
) {
    fun login(email: String, password: String, remoteAddress: String): StaffAuthResult {
        val now = clock.instant()
        val normalizedEmail = email.trim().lowercase(Locale.ROOT)
        val attemptKey = crypto.loginAttemptKey(normalizedEmail, remoteAddress)
        repository.lockedUntil(attemptKey, now)?.let { throw lockedError(now, it) }

        val profile = repository.findByEmail(normalizedEmail)
        if (!crypto.matchesPassword(password, profile?.account?.passwordHash)) {
            val failures = repository.recordFailedLogin(attemptKey, now, profile)
            if (failures >= MAX_LOGIN_FAILURES) {
                throw lockedError(
                    now,
                    repository.lockedUntil(attemptKey, now) ?: now.plusSeconds(LOGIN_LOCK_SECONDS),
                )
            }
            throw DomainException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password")
        }
        val authenticatedProfile = profile
            ?: throw DomainException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password")
        if (authenticatedProfile.account.status != ACTIVE_STATUS) {
            throw DomainException(ErrorCode.ACCOUNT_DISABLED, "Account is disabled")
        }

        repository.clearFailedLogins(attemptKey)
        val refreshToken = crypto.newRefreshToken()
        repository.storeRefreshToken(
            userId = authenticatedProfile.account.id,
            familyId = UUID.randomUUID(),
            tokenHash = crypto.hashRefreshToken(refreshToken),
            expiresAt = now.plusSeconds(REFRESH_LIFETIME_SECONDS),
            now = now,
        )
        return StaffAuthResult(
            profile = authenticatedProfile,
            accessToken = crypto.issueAccessToken(authenticatedProfile, now),
            refreshToken = refreshToken,
        )
    }

    fun refresh(refreshToken: String): StaffAuthResult {
        val now = clock.instant()
        val replacement = crypto.newRefreshToken()
        val rotation = repository.rotateRefreshToken(
            tokenHash = crypto.hashRefreshToken(refreshToken),
            replacementHash = crypto.hashRefreshToken(replacement),
            now = now,
            expiresAt = now.plusSeconds(REFRESH_LIFETIME_SECONDS),
        )
        return when (rotation) {
            is RefreshRotation.Rotated ->
                StaffAuthResult(rotation.profile, crypto.issueAccessToken(rotation.profile, now), replacement)
            RefreshRotation.Reused ->
                throw DomainException(ErrorCode.REFRESH_TOKEN_REUSED, "Refresh token reuse detected")
            RefreshRotation.Invalid ->
                throw DomainException(ErrorCode.UNAUTHENTICATED, "Invalid refresh token")
        }
    }

    fun me(subject: String?, restaurantClaim: Any?, tokenVersionClaim: Any?): StaffProfile {
        val profile = currentProfile(subject, restaurantClaim, tokenVersionClaim)
        if (profile.account.status != ACTIVE_STATUS) {
            throw DomainException(ErrorCode.ACCOUNT_DISABLED, "Account is disabled")
        }
        return profile
    }

    fun logout(subject: String?, restaurantClaim: Any?, tokenVersionClaim: Any?, refreshToken: String) {
        val profile = me(subject, restaurantClaim, tokenVersionClaim)
        repository.revokeRefreshToken(
            userId = profile.account.id,
            tokenHash = crypto.hashRefreshToken(refreshToken),
            now = clock.instant(),
        )
    }

    fun changePassword(
        subject: String?,
        restaurantClaim: Any?,
        tokenVersionClaim: Any?,
        currentPassword: String,
        newPassword: String,
    ) {
        val profile = me(subject, restaurantClaim, tokenVersionClaim)
        if (newPassword.length < MIN_PASSWORD_LENGTH) {
            throw DomainException(ErrorCode.PASSWORD_TOO_WEAK, "Password does not meet the minimum length")
        }
        if (!crypto.matchesPassword(currentPassword, profile.account.passwordHash)) {
            throw DomainException(ErrorCode.INVALID_CREDENTIALS, "Current password is incorrect")
        }
        repository.changePassword(
            userId = profile.account.id,
            passwordHash = crypto.encodePassword(newPassword),
            now = clock.instant(),
        )
    }

    private fun currentProfile(subject: String?, restaurantClaim: Any?, tokenVersionClaim: Any?): StaffProfile {
        val userId = try {
            UUID.fromString(subject)
        } catch (_: IllegalArgumentException) {
            throw DomainException(ErrorCode.UNAUTHENTICATED, "Invalid access token")
        }
        val profile = repository.findById(userId)
            ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "Invalid access token")
        val tokenVersion = tokenVersionClaim?.toString()?.toLongOrNull()
            ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "Invalid access token")
        if (profile.account.tokenVersion != tokenVersion ||
            restaurantClaim?.toString() != profile.account.restaurantId.toString()
        ) {
            throw DomainException(ErrorCode.UNAUTHENTICATED, "Access token is no longer valid")
        }
        return profile
    }

    private fun lockedError(now: Instant, lockedUntil: Instant): DomainException {
        val retryAfterSeconds = Duration.between(now, lockedUntil).seconds.coerceAtLeast(1)
        return DomainException(
            ErrorCode.ACCOUNT_TEMPORARILY_LOCKED,
            "Account is temporarily locked",
            mapOf("retryAfterSec" to retryAfterSeconds),
        )
    }

    private companion object {
        const val ACTIVE_STATUS = "ACTIVE"
        const val MAX_LOGIN_FAILURES = 5
        const val LOGIN_LOCK_SECONDS = 15 * 60L
        const val REFRESH_LIFETIME_SECONDS = 30 * 24 * 60 * 60L
        const val MIN_PASSWORD_LENGTH = 10
    }
}
