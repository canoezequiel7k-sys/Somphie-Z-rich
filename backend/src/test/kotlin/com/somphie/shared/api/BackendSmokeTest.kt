package com.somphie.shared.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Clock
import java.time.ZoneOffset

/** Comprueba arranque HTTP, metadatos, reloj y conectividad a PostgreSQL real. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class BackendSmokeTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var clock: Clock

    /** Verifica que la versión publicada coincida con los metadatos del build y el contrato. */
    @Test
    fun `version endpoint returns build and contract metadata`() {
        mockMvc.perform(get("/version"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.build").value("0.0.1-SNAPSHOT"))
            .andExpect(jsonPath("$.contractVersion").value("0.2.5"))
    }

    /** Confirma que Actuator exponga salud sin detalles internos en el perfil de pruebas. */
    @Test
    fun `health endpoint is available without infrastructure details`() {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
    }

    /** Prueba que la aplicación use la base PostgreSQL del contenedor en vez de una base simulada. */
    @Test
    fun `postgres container is reachable by the application`() {
        assertEquals(1, jdbcTemplate.queryForObject("SELECT 1", Int::class.java))
    }

    /** Fija UTC como zona del reloj compartido para evitar diferencias entre máquinas. */
    @Test
    fun `application clock uses UTC`() {
        assertEquals(ZoneOffset.UTC, clock.zone)
    }

    /** Comprueba en una base vacía que Flyway cree el esquema y aplique las semillas base una sola vez. */
    @Test
    fun `foundation migration creates required schema and seeds`() {
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success",
            Int::class.java,
        ))
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM restaurant", Int::class.java))
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM restaurant_settings", Int::class.java))
        assertEquals(4, jdbcTemplate.queryForObject("SELECT count(*) FROM role", Int::class.java))
        assertEquals(39, jdbcTemplate.queryForObject("SELECT count(*) FROM permission", Int::class.java))
        assertEquals(70, jdbcTemplate.queryForObject("SELECT count(*) FROM role_permission", Int::class.java))
        assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM station", Int::class.java))
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT count(*) FROM role_permission WHERE role_code = 'KITCHEN' AND permission_code = 'item.prepare'",
            Int::class.java,
        ))
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT count(*) FROM role_permission WHERE role_code = 'KITCHEN' AND permission_code = 'settings.manage'",
            Int::class.java,
        ))
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT count(*) FROM role_permission WHERE role_code = 'OWNER' AND permission_code = 'role.manage'",
            Int::class.java,
        ))
        assertEquals(15, jdbcTemplate.queryForObject(
            "SELECT max_staff_discount_percent FROM restaurant_settings",
            Int::class.java,
        ))
        assertEquals(100, jdbcTemplate.queryForObject(
            "SELECT max_tip_percent FROM restaurant_settings",
            Int::class.java,
        ))
    }

    private companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16.15-alpine")

        /** Inyecta la conexión temporal antes de crear el contexto Spring. */
        @DynamicPropertySource
        @JvmStatic
        fun configurePostgres(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
