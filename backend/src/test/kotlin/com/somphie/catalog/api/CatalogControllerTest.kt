package com.somphie.catalog.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CatalogControllerTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var restTemplateBuilder: RestTemplateBuilder

    companion object {
        @Container
        @JvmField
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16.15-alpine")
            .withDatabaseName("somphie_test")
            .withUsername("postgres")
            .withPassword("postgres")

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { "true" }
        }
    }

    private val restTemplate by lazy { restTemplateBuilder.rootUri("http://localhost:$port").build() }

    @Test
    fun `getMenu returns categories and products successfully`() {
        val response = restTemplate.getForEntity("/menu", Map::class.java)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.headers.etag)

        val body = response.body as Map<*, *>
        assertEquals(true, body["restaurantOpen"])
        val categories = body["categories"] as List<*>
        assertEquals(true, categories.isNotEmpty())
    }

    @Test
    fun `getMenu returns 304 when If-None-Match matches ETag`() {
        val firstResponse = restTemplate.getForEntity("/menu", Map::class.java)
        assertEquals(HttpStatus.OK, firstResponse.statusCode)
        val etag = firstResponse.headers.etag!!

        val headers = HttpHeaders().apply {
            set(HttpHeaders.IF_NONE_MATCH, etag)
        }
        val entity = HttpEntity<Void>(headers)

        val secondResponse = restTemplate.exchange("/menu", HttpMethod.GET, entity, Void::class.java)
        assertEquals(HttpStatus.NOT_MODIFIED, secondResponse.statusCode)
    }

    @Test
    fun `getProduct returns product detail successfully`() {
        val productId = "9a2b4c6d-8e0f-4a1b-b3c5-d7e9f1a3b5c7" // Flat white from V2 seed
        val response = restTemplate.getForEntity("/products/$productId", Map::class.java)
        assertEquals(HttpStatus.OK, response.statusCode)

        val body = response.body as Map<*, *>
        assertEquals("Flat white", body["name"])
        assertEquals(485000L, (body["priceMinor"] as Number).toLong())
    }
}
