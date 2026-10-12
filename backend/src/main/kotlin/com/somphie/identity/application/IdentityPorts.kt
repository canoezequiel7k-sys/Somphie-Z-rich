package com.somphie.identity.application

import com.somphie.identity.domain.RefreshRotation
import com.somphie.identity.domain.StaffProfile
import java.time.Instant
import java.util.UUID

interface IdentityRepository {
    fun findByEmail(email: String): StaffProfile?
    fun findById(id: UUID): StaffProfile?
    fun lockedUntil(attemptKey: String, now: Instant): Instant?
    fun recordFailedLogin(attemptKey: String, now: Instant, knownProfile: StaffProfile?): Int
    fun clearFailedLogins(attemptKey: String)
    fun storeRefreshToken(userId: UUID, familyId: UUID, tokenHash: String, expiresAt: Instant, now: Instant)
    fun rotateRefreshToken(tokenHash: String, replacementHash: String, now: Instant, expiresAt: Instant): RefreshRotation
    fun revokeRefreshToken(userId: UUID, tokenHash: String, now: Instant)
    fun changePassword(userId: UUID, passwordHash: String, now: Instant)
    fun seedDemoUsers(passwordHash: String)
}

interface IdentityCrypto {
    fun encodePassword(password: String): String
    fun matchesPassword(password: String, passwordHash: String?): Boolean
    fun issueAccessToken(profile: StaffProfile, now: Instant): String
    fun newRefreshToken(): String
    fun hashRefreshToken(token: String): String
    fun loginAttemptKey(email: String, remoteAddress: String): String
}
