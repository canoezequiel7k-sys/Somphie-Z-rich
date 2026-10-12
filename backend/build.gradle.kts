// Backend de Somphie Zúrich (ADR-0001, ADR-0002: un solo módulo Gradle, un paquete por módulo de negocio).
// Versiones verificadas el 2026-10-11 (ver docs/00-gobierno/advances.md, Paso 9).
// Spring Boot, JPA, Flyway, etc. se agregan en la Fase 2 (WP-BE-20): el dominio puro no los necesita.

plugins {
    kotlin("jvm") version "2.4.0"
    jacoco // Usa la cobertura incluida con Gradle, compatible con Kotlin/JVM sin sumar dependencias externas.
}

group = "com.somphie"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    // Kotlin 2.3+ puede generar bytecode para Java 25; con versiones anteriores quedaba limitado a Java 24.
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test) // Asegura que el reporte se construya con resultados de la suite vigente.
    reports {
        xml.required.set(true) // Permite que CI y herramientas lean la cobertura de forma estructurada.
        html.required.set(true) // Facilita revisar localmente qué líneas del dominio faltan cubrir.
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test) // No verifica un umbral usando datos de una ejecución anterior.
    violationRules {
        rule {
            limit {
                counter = "LINE" // El DoD de WP-BE-10 fija el umbral en cobertura de líneas.
                minimum = "0.90".toBigDecimal() // Falla si la cobertura total del dominio es menor al 90 %.
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification) // Convierte el umbral de cobertura en una puerta del build.
}
