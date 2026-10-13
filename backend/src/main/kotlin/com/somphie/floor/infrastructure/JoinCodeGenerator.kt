package com.somphie.floor.infrastructure

import com.somphie.floor.domain.JoinCode
import org.springframework.stereotype.Component
import java.security.SecureRandom

@Component
class JoinCodeGenerator {
    private val random = SecureRandom()
    private val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** Rotar exige un valor distinto para que el código anterior pierda validez inmediatamente. */
    fun generate(excluding: JoinCode? = null): JoinCode {
        var code: JoinCode
        do {
            code = JoinCode.of(buildString(6) {
                repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) }
            })
        } while (code == excluding)
        return code
    }
}
