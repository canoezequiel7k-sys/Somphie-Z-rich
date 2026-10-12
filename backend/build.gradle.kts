// Backend de Somphie Zúrich (ADR-0001, ADR-0002: un solo módulo Gradle, un paquete por módulo de negocio).
// Versiones verificadas el 2026-10-11 (ver docs/00-gobierno/advances.md, Paso 9).
// Spring Boot, JPA, Flyway, etc. se agregan en la Fase 2 (WP-BE-20): el dominio puro no los necesita.

plugins {
    kotlin("jvm") version "2.4.0"
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
