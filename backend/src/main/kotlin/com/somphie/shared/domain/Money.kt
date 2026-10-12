package com.somphie.shared.domain

/**
 * Dinero expresado en CENTAVOS (ADR-0004, regla de oro 10 de `backend.md`).
 *
 * ¿Por qué centavos enteros y no decimales?
 * Un `Double` no puede representar 0,10 exactamente (queda 0,1000000000000000055...), y al sumar
 * muchas líneas los errores se acumulan. Con un `Long` de centavos, 4850,00 es `485000` y todas
 * las sumas y comparaciones son exactas. Solo la pantalla formatea a "$ 4.850,00".
 *
 * ¿Por qué `value class`?
 * En tiempo de ejecución es solo un `Long` (no crea objetos, no cuesta memoria), pero el compilador
 * lo trata como un tipo propio: no se puede pasar por error un `Money` donde se espera una cantidad
 * de ítems, ni sumar un `Money` con un `Int`.
 *
 * ¿Por qué no admite negativos?
 * Un precio, un pago, una propina o un ajuste nunca son negativos. Lo único que SÍ puede ser negativo
 * es el saldo de la cuenta (si se pagó de más), y para eso existe otro tipo, `Balance`
 * (en `billing`). Separar los tipos evita errores del estilo "restar de más y no darse cuenta".
 *
 * Intentar crear un `Money` negativo es un error de PROGRAMACIÓN (no de negocio), por eso lanza
 * `IllegalArgumentException` y no un `DomainException` con código del catálogo.
 */
@JvmInline
value class Money private constructor(val minor: Long) : Comparable<Money> {

    init {
        // `require` lanza IllegalArgumentException si la condición es falsa.
        require(minor >= 0) { "Money cannot be negative (got $minor); use Balance for signed amounts" }
    }

    /** Suma exacta. `addExact` lanza ArithmeticException si el resultado no entra en un Long (nunca "da la vuelta"). */
    operator fun plus(other: Money): Money = Money(Math.addExact(minor, other.minor))

    /**
     * Multiplica por una cantidad (ej.: precio unitario × cantidad de la línea).
     * La cantidad no puede ser negativa. Usa `multiplyExact` por el mismo motivo que `plus`.
     */
    operator fun times(quantity: Int): Money {
        require(quantity >= 0) { "Quantity cannot be negative (got $quantity)" }
        return Money(Math.multiplyExact(minor, quantity.toLong()))
    }

    /** Compara por importe, así se pueden usar `<`, `>`, `minOf`, `maxOf` directamente con `Money`. */
    override fun compareTo(other: Money): Int = minor.compareTo(other.minor)

    val isZero: Boolean get() = minor == 0L
    val isPositive: Boolean get() = minor > 0L

    /**
     * Calcula un porcentaje expresado en PUNTOS BÁSICOS (1 % = 100 pb; 15 % = 1500 pb; 100 % = 10000 pb)
     * y lo redondea AL CENTAVO MÁS CERCANO, LA MITAD HACIA ARRIBA (RN-ADJ-03).
     *
     * ¿Por qué puntos básicos? Para no usar decimales: "12,5 %" es `1250`.
     *
     * ¿Cómo se redondea sin decimales? Con aritmética entera: `(importe × pb + 5000) / 10000`.
     * Sumar 5000 (la mitad de 10000) antes de dividir hace que la división entera (que trunca)
     * redondee hacia arriba cuando el resto es 0,5 o más. Ejemplo: 1005 × 10 % = 100,5 → da 101.
     */
    fun percentBp(basisPoints: Int): Money {
        require(basisPoints in 0..10_000) { "Basis points must be between 0 and 10000 (got $basisPoints)" }
        val scaled = Math.multiplyExact(minor, basisPoints.toLong())
        return Money(Math.addExact(scaled, 5_000L) / 10_000L)
    }

    override fun toString(): String = "Money($minor)"

    companion object {
        val ZERO = Money(0)

        /** Única forma de crear un `Money` desde un número de centavos (el constructor es privado). */
        fun of(minor: Long): Money = Money(minor)
    }
}

/**
 * Suma una lista de importes. Una lista vacía suma cero.
 * Se llama `total` (y no `sum`) para no confundirse con la `sum()` de Kotlin para números.
 */
fun Iterable<Money>.total(): Money = fold(Money.ZERO) { acc, money -> acc + money }
