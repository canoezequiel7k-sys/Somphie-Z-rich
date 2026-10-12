package com.somphie.identity.infrastructure

import com.somphie.identity.application.IdentityCrypto
import com.somphie.identity.domain.StaffProfile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

@Component
class IdentityCryptoAdapter(
    private val passwordEncoder: PasswordEncoder,
    private val jwtEncoder: JwtEncoder,
) : IdentityCrypto {
    private val secureRandom = SecureRandom()
    private val dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString())

    override fun encodePassword(password: String): String =
        passwordEncoder.encode(password) ?: throw IllegalStateException("Password encoder returned no hash")

    override fun matchesPassword(password: String, passwordHash: String?): Boolean =
        passwordEncoder.matches(password, passwordHash ?: dummyPasswordHash) && passwordHash != null

    override fun issueAccessToken(profile: StaffProfile, now: Instant): String {
        val claims = JwtClaimsSet.builder()
            .issuer(JWT_ISSUER)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(ACCESS_LIFETIME_SECONDS))
            .subject(profile.account.id.toString())
            .claim("rid", profile.account.restaurantId.toString())
            .claim("tv", profile.account.tokenVersion)
            .claim("roles", profile.roles)
            .claim("perms", profile.permissions)
            .claim("mustChangePassword", profile.account.mustChangePassword)
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }

    override fun newRefreshToken(): String {
        val bytes = ByteArray(REFRESH_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    override fun hashRefreshToken(token: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

    override fun loginAttemptKey(email: String, remoteAddress: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest("$email\u0000$remoteAddress".toByteArray(Charsets.UTF_8)),
        )

    private companion object {
        const val JWT_ISSUER = "somphie-backend"
        const val ACCESS_LIFETIME_SECONDS = 15 * 60L
        const val REFRESH_TOKEN_BYTES = 32
    }
}
