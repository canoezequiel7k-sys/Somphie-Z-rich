package com.somphie.catalog.api

import com.somphie.catalog.domain.CategoryDto
import com.somphie.catalog.domain.ImageDto
import com.somphie.catalog.domain.MenuResponse
import com.somphie.catalog.domain.ProductDto
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class CatalogController(
    private val jdbc: NamedParameterJdbcTemplate
) {

    private val defaultRestaurantId = "6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68"

    @GetMapping("/menu")
    fun getMenu(
        @RequestParam(required = false) restaurantId: String?,
        @RequestParam(required = false) lang: String?,
        @RequestHeader(name = HttpHeaders.ACCEPT_LANGUAGE, required = false) acceptLanguage: String?,
        @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?
    ): ResponseEntity<MenuResponse> {
        val targetRestaurantId = restaurantId ?: defaultRestaurantId

        // Validate restaurant exists
        val restaurantExists = jdbc.queryForObject(
            "SELECT COUNT(1) FROM restaurant WHERE id = CAST(:id AS UUID)",
            mapOf("id" to targetRestaurantId),
            Int::class.java
        ) ?: 0

        if (restaurantExists == 0) {
            throw DomainException(ErrorCode.NOT_FOUND, "Restaurant not found")
        }

        val effectiveLocale = lang ?: acceptLanguage?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: "es-AR"

        // Get currency and open status
        val currency = jdbc.queryForObject(
            "SELECT currency FROM restaurant WHERE id = CAST(:id AS UUID)",
            mapOf("id" to targetRestaurantId),
            String::class.java
        ) ?: "ARS"

        val catalogVersion = 1L
        val etag = W/"$catalogVersion-$effectiveLocale"

        if (ifNoneMatch != null && ifNoneMatch.replace("W/", "") == etag.replace("W/", "")) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .header(HttpHeaders.ETAG, etag)
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE)
                .build()
        }

        // Fetch categories and products
        val categories = fetchCategories(targetRestaurantId)

        val response = MenuResponse(
            catalogVersion = catalogVersion,
            locale = effectiveLocale,
            currency = currency,
            restaurantOpen = true,
            categories = categories
        )

        return ResponseEntity.ok()
            .header(HttpHeaders.ETAG, etag)
            .header(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE)
            .body(response)
    }

    @GetMapping("/products/{productId}")
    fun getProduct(
        @PathVariable productId: String,
        @RequestParam(required = false) lang: String?,
        @RequestHeader(name = HttpHeaders.ACCEPT_LANGUAGE, required = false) acceptLanguage: String?
    ): ResponseEntity<ProductDto> {
        val uuid = try {
            UUID.fromString(productId)
        } catch (e: Exception) {
            throw DomainException(ErrorCode.NOT_FOUND, "Product not found")
        }

        val sql = """
            SELECT p.id, p.name, p.description, p.price_minor, p.available, s.code AS station_code,
                   p.allergens, p.image_path, p.image_version, p.image_alt
            FROM product p
            JOIN station s ON p.station_id = s.id
            WHERE p.id = CAST(:id AS UUID)
        """

        val products = jdbc.query(sql, mapOf("id" to uuid.toString())) { rs, _ ->
            val imgPath = rs.getString("image_path")
            val image = if (imgPath != null) {
                ImageDto(
                    path = imgPath,
                    version = rs.getInt("image_version"),
                    alt = rs.getString("image_alt")
                )
            } else null

            @Suppress("UNCHECKED_CAST")
            val allergensArray = rs.getArray("allergens")?.array as? Array<String> ?: emptyArray()

            ProductDto(
                id = rs.getString("id"),
                name = rs.getString("name"),
                description = rs.getString("description"),
                priceMinor = rs.getLong("price_minor"),
                available = rs.getBoolean("available"),
                station = rs.getString("station_code"),
                allergens = allergensArray.toList(),
                image = image
            )
        }

        val product = products.firstOrNull() ?: throw DomainException(ErrorCode.NOT_FOUND, "Product not found")
        return ResponseEntity.ok(product)
    }

    private fun fetchCategories(restaurantId: String): List<CategoryDto> {
        val catSql = """
            SELECT id, name, sort_order
            FROM category
            WHERE restaurant_id = CAST(:restaurantId AS UUID)
            ORDER BY sort_order ASC
        """

        return jdbc.query(catSql, mapOf("restaurantId" to restaurantId)) { rs, _ ->
            val catId = rs.getString("id")
            val catName = rs.getString("name")
            val sortOrder = rs.getInt("sort_order")
            val products = fetchProductsForCategory(catId)

            CategoryDto(
                id = catId,
                name = catName,
                sortOrder = sortOrder,
                products = products
            )
        }
    }

    private fun fetchProductsForCategory(categoryId: String): List<ProductDto> {
        val prodSql = """
            SELECT p.id, p.name, p.description, p.price_minor, p.available, s.code AS station_code,
                   p.allergens, p.image_path, p.image_version, p.image_alt
            FROM product p
            JOIN subcategory sub ON p.subcategory_id = sub.id
            JOIN station s ON p.station_id = s.id
            WHERE sub.category_id = CAST(:categoryId AS UUID)
            ORDER BY p.sort_order ASC
        """

        return jdbc.query(prodSql, mapOf("categoryId" to categoryId)) { rs, _ ->
            val imgPath = rs.getString("image_path")
            val image = if (imgPath != null) {
                ImageDto(
                    path = imgPath,
                    version = rs.getInt("image_version"),
                    alt = rs.getString("image_alt")
                )
            } else null

            @Suppress("UNCHECKED_CAST")
            val allergensArray = rs.getArray("allergens")?.array as? Array<String> ?: emptyArray()

            ProductDto(
                id = rs.getString("id"),
                name = rs.getString("name"),
                description = rs.getString("description"),
                priceMinor = rs.getLong("price_minor"),
                available = rs.getBoolean("available"),
                station = rs.getString("station_code"),
                allergens = allergensArray.toList(),
                image = image
            )
        }
    }
}
