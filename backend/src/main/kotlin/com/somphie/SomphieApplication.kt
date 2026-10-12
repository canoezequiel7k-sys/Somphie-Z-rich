package com.somphie

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** Punto de entrada del monolito modular: Spring busca aquí los componentes bajo `com.somphie`. */
@SpringBootApplication
class SomphieApplication

/** Inicia el servidor con perfiles y configuración externos, sin acoplarlos al dominio. */
fun main(args: Array<String>) {
    runApplication<SomphieApplication>(*args)
}
