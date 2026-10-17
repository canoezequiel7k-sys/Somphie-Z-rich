package com.somphie.shared.idempotency

import org.springframework.stereotype.Component
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface IdempotencyResponseCodec {
    fun encode(body: Map<String, Any>): Map<String, Any>
    fun decode(body: Map<String, Any>): Map<String, Any>
}

@Component
class GuestTokenIdempotencyResponseCodec(secretKey: SecretKey) : IdempotencyResponseCodec {
    private val encryptionKey = SecretKeySpec(
        Mac.getInstance("HmacSHA256").run {
            init(secretKey)
            doFinal(KEY_DERIVATION_CONTEXT)
        },
        "AES",
    )

    override fun encode(body: Map<String, Any>): Map<String, Any> {
        if (!body.containsKey("guestToken")) return body
        val guestToken = body["guestToken"] as? String
            ?: throw IllegalArgumentException("Guest token response field must be a string")
        val nonce = ByteArray(NONCE_SIZE).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(GUEST_TOKEN_FIELD)
        val ciphertext = cipher.doFinal(guestToken.toByteArray(Charsets.UTF_8))
        val protectedToken = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce + ciphertext)
        return body + ("guestToken" to "$ENCODED_TOKEN_PREFIX$protectedToken")
    }

    override fun decode(body: Map<String, Any>): Map<String, Any> {
        if (!body.containsKey("guestToken")) return body
        val encodedToken = body["guestToken"] as? String
            ?: throw IllegalStateException("Stored guest token response field is invalid")
        check(encodedToken.startsWith(ENCODED_TOKEN_PREFIX)) {
            "Stored guest token response is not encrypted"
        }
        val encrypted = Base64.getUrlDecoder().decode(encodedToken.removePrefix(ENCODED_TOKEN_PREFIX))
        check(encrypted.size > NONCE_SIZE) { "Stored guest token response is invalid" }
        val nonce = encrypted.copyOfRange(0, NONCE_SIZE)
        val ciphertext = encrypted.copyOfRange(NONCE_SIZE, encrypted.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(GUEST_TOKEN_FIELD)
            body + ("guestToken" to String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        } catch (exception: GeneralSecurityException) {
            throw IllegalStateException("Stored guest token response could not be decrypted", exception)
        }
    }

    private companion object {
        const val TAG_BITS = 128
        const val NONCE_SIZE = 12
        const val ENCODED_TOKEN_PREFIX = "enc:v1:"
        val KEY_DERIVATION_CONTEXT = "somphie:idempotency-response:v1".toByteArray(Charsets.UTF_8)
        val GUEST_TOKEN_FIELD = "guestToken".toByteArray(Charsets.UTF_8)
        val secureRandom = java.security.SecureRandom()
    }
}
