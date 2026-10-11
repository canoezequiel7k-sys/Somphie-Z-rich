# ADR-0001: Stack tecnológico
**Estado:** propuesta (derivada de D-015)

## Contexto
Proyecto personal, presupuesto cero, un solo desarrollador con Android intermedio y web/backend principiante.

## Decisión
- **Backend:** Spring Boot + Kotlin, Gradle (Kotlin DSL), PostgreSQL, Spring Data JPA, Flyway, Spring Security, Bean Validation, springdoc solo como visor opcional.
- **Web:** React + TypeScript (Vite), React Router, TanStack Query, react-hook-form + zod, Zustand (carrito), CSS con tokens + Radix primitives, Motion para animaciones, Vitest + RTL + MSW, Playwright (E2E).
- **Android:** Kotlin, Jetpack Compose, Material 3 personalizado, Hilt, Retrofit/OkHttp + kotlinx.serialization, DataStore + Keystore, Navigation Compose, JUnit/MockK/Turbine/Compose UI Test.
- **Pruebas backend:** JUnit 5, Testcontainers (PostgreSQL), ArchUnit, swagger-request-validator.
- **Infra local:** Docker Compose (PostgreSQL, Mailpit, Prism).

## Motivos
Coherente con D-015; ecosistemas maduros, mucha documentación, aprendizaje transferible.

## Alternativas
Next.js (SSR innecesario para este caso), Flutter (descartado: ya hay base Android nativa), Ktor (menos "todo incluido" para un principiante en backend).

## Consecuencias
Docker Desktop (WSL2) necesario en Windows para Testcontainers. **Verificar versiones estables vigentes** (JDK LTS, Spring Boot, Node LTS, AGP) al iniciar Fase 2 y fijarlas.
