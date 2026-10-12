// Backend de Somphie Zúrich (ADR-0001, ADR-0002: un solo módulo Gradle, un paquete por módulo de negocio).
// El dominio puro se conserva; WP-BE-20 agrega el runtime Spring sin mover sus reglas.
// Versiones de Spring y herramientas fijadas para la implementación de Fase 2.

plugins {
    kotlin("jvm") version "2.4.0" // Mantiene el compilador Kotlin fijado y compatible con Java 25.
    kotlin("plugin.spring") version "2.4.0" // Abre las clases Spring que necesitan proxies para inyección y tests.
    id("org.springframework.boot") version "4.1.1" // Fija la versión estable del runtime Spring Boot.
    jacoco // Usa la cobertura incluida con Gradle, compatible con Kotlin/JVM sin sumar dependencias externas.
}

group = "com.somphie"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1")) // Importa versiones alineadas sin sumar otro plugin de gestión.
    implementation("org.springframework.boot:spring-boot-starter-web") // Expone REST MVC y serialización JSON.
    implementation("org.springframework.boot:spring-boot-starter-actuator") // Agrega health/readiness sin publicar métricas sensibles.
    implementation("org.springframework.boot:spring-boot-starter-jdbc") // Conecta la aplicación a PostgreSQL hasta que llegue JPA.
    implementation("org.springframework.boot:spring-boot-starter-validation") // Habilita validación estándar de solicitudes HTTP.
    implementation("org.springframework.boot:spring-boot-starter-security") // Provee BCrypt, filtros sin sesión y soporte de autorización.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server") // Usa JWT/JWK de Spring Security para validar y emitir access tokens.
    implementation("org.jetbrains.kotlin:kotlin-reflect") // Permite que Spring inspeccione constructores y tipos Kotlin.
    implementation("org.springframework.boot:spring-boot-starter-flyway") // Registra la auto-configuración de migraciones de Spring Boot.
    runtimeOnly("org.postgresql:postgresql") // Provee el driver de PostgreSQL configurado por variables de entorno.
    runtimeOnly("org.flywaydb:flyway-database-postgresql") // Habilita el dialecto PostgreSQL requerido por Flyway.
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1")) // Alinea las dependencias de prueba con el runtime.
    testImplementation("org.springframework.boot:spring-boot-starter-test") // Reutiliza las utilidades de pruebas del stack Spring.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test") // Añade MockMvc, separado del starter general en Boot 4.
    testImplementation(platform("org.junit:junit-bom:6.1.2")) // Conserva la versión JUnit ya adoptada por WP-BE-10.
    testImplementation("org.junit.jupiter:junit-jupiter") // Mantiene la suite del dominio sobre JUnit Jupiter.
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4")) // Fija una versión estable compartida de Testcontainers.
    testImplementation("org.testcontainers:junit-jupiter") // Integra los contenedores con el ciclo de vida de JUnit.
    testImplementation("org.testcontainers:postgresql") // Permite probar la conexión contra PostgreSQL real.
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1") // Verifica dependencias permitidas entre capas y módulos.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher") // Provee el launcher requerido por Gradle para ejecutar JUnit.
}

springBoot { // Genera metadatos de build para responder /version desde la versión Gradle.
    buildInfo() // Evita duplicar manualmente la versión de compilación en la API.
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
