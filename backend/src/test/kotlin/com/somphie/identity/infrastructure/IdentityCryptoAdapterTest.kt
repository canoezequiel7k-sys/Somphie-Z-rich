package com.somphie.identity.infrastructure

import com.somphie.identity.domain.StaffAccount
import com.somphie.identity.domain.StaffProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.oauth2.jwt.JwtException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

class IdentityCryptoAdapterTest {
    private val secretKey = SecretKeySpec(
        Base64.getDecoder().decode("dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1zb21waGllLTIwMjY="),
        "HmacSHA256",
    )
    private val security = IdentitySecurityConfiguration()
    private val crypto = IdentityCryptoAdapter(
        BCryptPasswordEncoder(12),
        security.jwtEncoder(secretKey),
    )
    private val decoder = security.jwtDecoder(secretKey)
    private val profile = StaffProfile(
        account = StaffAccount(
            id = USER_ID,
            restaurantId = RESTAURANT_ID,
            email = "waiter@somphie.demo",
            passwordHash = "unused",
            displayName = "Waiter",
            status = "ACTIVE",
            tokenVersion = 3,
            mustChangePassword = true,
        ),
        roles = listOf("WAITER"),
        permissions = listOf("order.accept"),
        stations = emptyList(),
    )

    @Test
    fun `access token is signed with current account and expires in fifteen minutes`() {
        val issuedAt = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.SECONDS)
        val token = crypto.issueAccessToken(profile, issuedAt)
        val decoded = decoder.decode(token)

        assertEquals(USER_ID.toString(), decoded.subject)
        assertEquals(RESTAURANT_ID.toString(), decoded.getClaimAsString("rid"))
        assertEquals(3L, decoded.getClaim<Number>("tv")?.toLong())
        assertEquals(listOf("WAITER"), decoded.getClaimAsStringList("roles"))
        assertEquals(listOf("order.accept"), decoded.getClaimAsStringList("perms"))
        assertEquals(true, decoded.getClaim<Boolean>("mustChangePassword"))
        assertEquals(issuedAt.plusSeconds(15 * 60L), decoded.expiresAt)
    }

    @Test
    fun `refresh token is opaque and only its sha256 digest is suitable for persistence`() {
        val token = crypto.newRefreshToken()
        val digest = crypto.hashRefreshToken(token)

        assertTrue(token.length >= 40)
        assertEquals(64, digest.length)
        assertNotEquals(token, digest)
    }

    @Test
    fun `password is bcrypt hashed and expired access tokens are rejected`() {
        val encoded = crypto.encodePassword("a-long-demo-password")
        assertNotEquals("a-long-demo-password", encoded)
        assertTrue(crypto.matchesPassword("a-long-demo-password", encoded))
        assertThrows<JwtException> {
            decoder.decode(crypto.issueAccessToken(profile, Instant.now().minusSeconds(1800)))
        }
    }

    private companion object {
        val USER_ID = UUID.fromString("e6b8be16-51c5-4835-99f1-10d2259ee002")
        val RESTAURANT_ID = UUID.fromString("6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68")
    }
}
