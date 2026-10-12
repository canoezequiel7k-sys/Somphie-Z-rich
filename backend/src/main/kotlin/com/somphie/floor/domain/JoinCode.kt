package com.somphie.floor.domain

/**
 * Código de unión aceptado por el dominio: seis caracteres y sin símbolos ambiguos.
 * La generación aleatoria y la comprobación de colisiones pertenecen a la infraestructura.
 */
@JvmInline
value class JoinCode private constructor(val value: String) {

    companion object {
        private const val LENGTH = 6
        private const val ALLOWED_CHARACTERS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        /** Valida la forma del código sin decidir cómo se genera ni dónde se almacena. */
        fun of(value: String): JoinCode {
            require(value.length == LENGTH) { "Join code must contain exactly $LENGTH characters" } // Protege la longitud contractual.
            require(value.all { it in ALLOWED_CHARACTERS }) { "Join code contains an ambiguous or invalid character" } // Excluye letras confundibles y símbolos ajenos.
            return JoinCode(value) // Solo se crea el objeto una vez validado el formato.
        }
    }
}
