package com.somphie.shared.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Publica el reloj del sistema como dependencia para que los casos de uso puedan sustituirlo en pruebas. */
@Configuration
class ClockConfiguration {
    /** Usa UTC como referencia única y evita que cada módulo consulte la hora directamente. */
    @Bean
    fun applicationClock(): Clock = Clock.systemUTC()
}
