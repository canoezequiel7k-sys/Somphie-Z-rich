package com.somphie.identity.application

import com.somphie.identity.domain.RefreshRotation
import com.somphie.identity.domain.StaffAccount
import com.somphie.identity.domain.StaffProfile
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class IdentityServiceTest {
    private val repository = mock(IdentityRepository::class.java)
    private val crypto = mock(IdentityCrypto::class.java)
    private val service = IdentityService(repository, crypto, Clock.fixed(NOW, ZoneOffset.UTC))
    private val profile = StaffProfile(
        account = StaffAccount(
            id = USER_ID,
            restaurantId = RESTAURANT_ID,
            email = "waiter@somphie.demo",
            passwordHash = "bcrypt-hash",
            displayName = "Waiter",
            status = "ACTIVE",
            tokenVersion = 0,
            mustChangePassword = false,
        ),
        roles = listOf("WAITER"),
        permissions = listOf("order.accept"),
        stations = emptyList(),
    )

    @Test
    fun `login issues access and refresh tokens from current user profile`() {
        given(crypto.loginAttemptKey("waiter@somphie.demo", "127.0.0.1")).willReturn("attempt-hash")
        given(repository.lockedUntil("attempt-hash", NOW)).willReturn(null)
        given(repository.findByEmail("waiter@somphie.demo")).willReturn(profile)
        given(crypto.matchesPassword("secret-password", "bcrypt-hash")).willReturn(true)
        given(crypto.newRefreshToken()).willReturn("opaque-refresh")
        given(crypto.hashRefreshToken("opaque-refresh")).willReturn("hashed-refresh")
        given(crypto.issueAccessToken(profile, NOW)).willReturn("signed-access")

        val result = service.login("Waiter@Somphie.demo", "secret-password", "127.0.0.1")

        assertEquals("signed-access", result.accessToken)
        assertEquals("opaque-refresh", result.refreshToken)
        assertEquals(profile, result.profile)
        verify(repository).clearFailedLogins("attempt-hash")
    }

    @Test
    fun `unknown and incorrect accounts share invalid credentials error`() {
        given(crypto.loginAttemptKey("missing@somphie.demo", "127.0.0.1")).willReturn("attempt-hash")
        given(repository.lockedUntil("attempt-hash", NOW)).willReturn(null)
        given(repository.findByEmail("missing@somphie.demo")).willReturn(null)
        given(crypto.matchesPassword("wrong-password", null)).willReturn(false)
        given(repository.recordFailedLogin("attempt-hash", NOW, null)).willReturn(1)

        val exception = assertThrows(DomainException::class.java) {
            service.login("missing@somphie.demo", "wrong-password", "127.0.0.1")
        }

        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.code)
    }

    @Test
    fun `fifth failed login locks the account and returns retry interval`() {
        given(crypto.loginAttemptKey("waiter@somphie.demo", "127.0.0.1")).willReturn("attempt-hash")
        given(repository.lockedUntil("attempt-hash", NOW)).willReturn(null)
        given(repository.findByEmail("waiter@somphie.demo")).willReturn(profile)
        given(crypto.matchesPassword("wrong-password", "bcrypt-hash")).willReturn(false)
        given(repository.recordFailedLogin("attempt-hash", NOW, profile)).willReturn(5)

        val exception = assertThrows(DomainException::class.java) {
            service.login("waiter@somphie.demo", "wrong-password", "127.0.0.1")
        }

        assertEquals(ErrorCode.ACCOUNT_TEMPORARILY_LOCKED, exception.code)
        assertEquals(900L, exception.extra["retryAfterSec"])
    }

    @Test
    fun `locked login stops before checking credentials`() {
        val lockedUntil = NOW.plusSeconds(30)
        given(crypto.loginAttemptKey("waiter@somphie.demo", "127.0.0.1")).willReturn("attempt-hash")
        given(repository.lockedUntil("attempt-hash", NOW)).willReturn(lockedUntil)

        val exception = assertThrows(DomainException::class.java) {
            service.login(" Waiter@Somphie.demo ", "password", "127.0.0.1")
        }

        assertEquals(ErrorCode.ACCOUNT_TEMPORARILY_LOCKED, exception.code)
        assertEquals(30L, exception.extra["retryAfterSec"])
        verify(repository, never()).findByEmail("waiter@somphie.demo")
    }

    @Test
    fun `disabled account cannot login even with a valid password`() {
        val disabledProfile = profile.copy(account = profile.account.copy(status = "DISABLED"))
        given(crypto.loginAttemptKey("waiter@somphie.demo", "127.0.0.1")).willReturn("attempt-hash")
        given(repository.lockedUntil("attempt-hash", NOW)).willReturn(null)
        given(repository.findByEmail("waiter@somphie.demo")).willReturn(disabledProfile)
        given(crypto.matchesPassword("secret-password", "bcrypt-hash")).willReturn(true)

        val exception = assertThrows(DomainException::class.java) {
            service.login("waiter@somphie.demo", "secret-password", "127.0.0.1")
        }

        assertEquals(ErrorCode.ACCOUNT_DISABLED, exception.code)
        verify(repository).lockedUntil("attempt-hash", NOW)
        verify(repository).findByEmail("waiter@somphie.demo")
        verifyNoMoreInteractions(repository)
    }

    @Test
    fun `invalid refresh token is rejected without issuing a session`() {
        given(crypto.hashRefreshToken("unknown-refresh")).willReturn("unknown-hash")
        given(crypto.newRefreshToken()).willReturn("replacement")
        given(crypto.hashRefreshToken("replacement")).willReturn("replacement-hash")
        given(
            repository.rotateRefreshToken(
                "unknown-hash",
                "replacement-hash",
                NOW,
                NOW.plusSeconds(30L * 24 * 60 * 60),
            ),
        ).willReturn(RefreshRotation.Invalid)

        val exception = assertThrows(DomainException::class.java) {
            service.refresh("unknown-refresh")
        }

        assertEquals(ErrorCode.UNAUTHENTICATED, exception.code)
    }

    @Test
    fun `reused refresh token is surfaced after repository revokes its family`() {
        given(crypto.hashRefreshToken("reused")).willReturn("stored-hash")
        given(crypto.newRefreshToken()).willReturn("replacement")
        given(crypto.hashRefreshToken("replacement")).willReturn("replacement-hash")
        given(
            repository.rotateRefreshToken(
                "stored-hash",
                "replacement-hash",
                NOW,
                NOW.plusSeconds(30L * 24 * 60 * 60),
            ),
        ).willReturn(RefreshRotation.Reused)

        val exception = assertThrows(DomainException::class.java) { service.refresh("reused") }

        assertEquals(ErrorCode.REFRESH_TOKEN_REUSED, exception.code)
    }

    @Test
    fun `me rejects stale restaurant and token version claims`() {
        given(repository.findById(USER_ID)).willReturn(profile)

        val exception = assertThrows(DomainException::class.java) {
            service.me(USER_ID.toString(), UUID.randomUUID(), profile.account.tokenVersion)
        }

        assertEquals(ErrorCode.UNAUTHENTICATED, exception.code)
    }

    @Test
    fun `me rejects disabled accounts`() {
        given(repository.findById(USER_ID)).willReturn(
            profile.copy(account = profile.account.copy(status = "DISABLED")),
        )

        val exception = assertThrows(DomainException::class.java) {
            service.me(USER_ID.toString(), RESTAURANT_ID, profile.account.tokenVersion)
        }

        assertEquals(ErrorCode.ACCOUNT_DISABLED, exception.code)
    }

    @Test
    fun `logout revokes only the authenticated users matching refresh token`() {
        given(repository.findById(USER_ID)).willReturn(profile)
        given(crypto.hashRefreshToken("refresh-token")).willReturn("refresh-hash")

        service.logout(USER_ID.toString(), RESTAURANT_ID, profile.account.tokenVersion, "refresh-token")

        verify(repository).revokeRefreshToken(USER_ID, "refresh-hash", NOW)
    }

    @Test
    fun `password change rejects weak and incorrect passwords without persistence`() {
        given(repository.findById(USER_ID)).willReturn(profile)

        val weakPassword = assertThrows(DomainException::class.java) {
            service.changePassword(
                USER_ID.toString(), RESTAURANT_ID, profile.account.tokenVersion, "current", "too-short",
            )
        }
        assertEquals(ErrorCode.PASSWORD_TOO_WEAK, weakPassword.code)

        given(crypto.matchesPassword("wrong-current", "bcrypt-hash")).willReturn(false)
        val wrongPassword = assertThrows(DomainException::class.java) {
            service.changePassword(
                USER_ID.toString(), RESTAURANT_ID, profile.account.tokenVersion, "wrong-current", "long-enough",
            )
        }
        assertEquals(ErrorCode.INVALID_CREDENTIALS, wrongPassword.code)
        verify(repository, never()).changePassword(USER_ID, "new-bcrypt-hash", NOW)
    }

    @Test
    fun `password change persists a hash for the authenticated account`() {
        given(repository.findById(USER_ID)).willReturn(profile)
        given(crypto.matchesPassword("current", "bcrypt-hash")).willReturn(true)
        given(crypto.encodePassword("long-enough")).willReturn("new-bcrypt-hash")

        service.changePassword(
            USER_ID.toString(), RESTAURANT_ID, profile.account.tokenVersion, "current", "long-enough",
        )

        verify(repository).changePassword(USER_ID, "new-bcrypt-hash", NOW)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-12T12:00:00Z")
        val USER_ID: UUID = UUID.fromString("e6b8be16-51c5-4835-99f1-10d2259ee002")
        val RESTAURANT_ID: UUID = UUID.fromString("6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68")
    }
}
