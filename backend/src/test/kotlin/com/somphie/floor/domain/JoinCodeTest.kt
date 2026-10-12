package com.somphie.floor.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Comprueba la longitud y el alfabeto exactos definidos para el código de sesión. */
class JoinCodeTest {
    @Test
    fun `accepts every documented character in a six-character code`() {
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        alphabet.forEach { character ->
            assertEquals("$character" + "AAAAA", JoinCode.of("$character" + "AAAAA").value)
        }
    }

    /** Los códigos cortos o largos no pueden representar el formato contractual. */
    @Test
    fun `rejects values whose length differs from six`() {
        assertThrows(IllegalArgumentException::class.java) { JoinCode.of("ABCDE") }
        assertThrows(IllegalArgumentException::class.java) { JoinCode.of("ABCDEFG") }
        assertThrows(IllegalArgumentException::class.java) { JoinCode.of("") }
    }

    /** Se excluyen explícitamente los caracteres confundibles o ajenos al alfabeto mayúsculo. */
    @Test
    fun `rejects ambiguous lowercase and punctuation characters`() {
        listOf("0", "O", "1", "I", "L", "a", "-", " ").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                JoinCode.of("ABCDE$invalid")
            }
        }
    }
}
