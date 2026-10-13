package com.somphie.catalog.api

import com.somphie.catalog.domain.CategoryDto
import com.somphie.catalog.domain.ImageDto
import com.somphie.catalog.domain.MenuResponse
import com.somphie.catalog.domain.OptionDto
import com.somphie.catalog.domain.OptionGroupDto
import com.somphie.catalog.domain.ProductDto
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.springframework.boot.json.JsonParserFactory
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
    private val jsonParser = JsonParserFactory.getJsonParser()

    @GetMapping("/menu")
    fun getMenu(
        @RequestParam(required = false) restaurantId: String?,
        @RequestParam(required = false) lang: String?,
        @RequestHeader(name = HttpHeaders.ACCEPT_LANGUAGE, required = false) acceptLanguage: String?,
        @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?
    ): ResponseEntity<MenuResponse> {
        val targetRestaurantId = restaurantId ?: defaultRestaurantId

        val restaurantExists = jdbc.queryForObject(
            "SELECT COUNT(1) FROM restaurant WHERE id = CAST(:id AS UUID)",
            mapOf("id" to targetRestaurantId),
            Int::class.java
        ) ?: 0

        if (restaurantExists == 0) {
            throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Restaurant not found")
        }

        val effectiveLocale = lang ?: acceptLanguage?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: "es-AR"

        val currency = jdbc.queryForObject(
            "SELECT currency FROM restaurant WHERE id = CAST(:id AS UUID)",
            mapOf("id" to targetRestaurantId),
            String::class.java
        ) ?: "ARS"

        val catalogVersion = 2L
        val etag = "W/\"$catalogVersion-$effectiveLocale\""

        if (ifNoneMatch != null && ifNoneMatch.replace("W/", "") == etag.replace("W/", "")) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .header(HttpHeaders.ETAG, etag)
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE)
                .build()
        }

        val categories = fetchCategories(targetRestaurantId, effectiveLocale)

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
            throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Product not found")
        }

        val effectiveLocale = lang ?: acceptLanguage?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: "es-AR"

        val sql = """
            SELECT p.id, p.name, p.name_i18n, p.description, p.description_i18n, p.price_minor, p.available, s.code AS station_code,
                   p.allergens, p.image_path, p.image_version, p.image_alt
            FROM product p
            JOIN station s ON p.station_id = s.id
            JOIN subcategory sub ON sub.id = p.subcategory_id
            JOIN category c ON c.id = sub.category_id
            WHERE p.id = CAST(:id AS UUID) AND p.active = TRUE AND c.active = TRUE
        """

        val products = jdbc.query(sql, mapOf("id" to uuid.toString())) { rs, _ ->
            val nameJson = rs.getString("name_i18n")
            val resolvedName = resolveI18n(nameJson, effectiveLocale) ?: rs.getString("name")

            val descJson = rs.getString("description_i18n")
            val resolvedDesc = resolveI18n(descJson, effectiveLocale) ?: rs.getString("description")

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

            val productIdStr = rs.getString("id")
            val optionGroups = fetchOptionGroupsForProduct(productIdStr, effectiveLocale)

            ProductDto(
                id = productIdStr,
                name = resolvedName,
                description = resolvedDesc,
                priceMinor = rs.getLong("price_minor"),
                available = rs.getBoolean("available"),
                station = rs.getString("station_code"),
                allergens = allergensArray.toList(),
                image = image,
                optionGroups = optionGroups
            )
        }

        val product = products.firstOrNull() ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Product not found")
        return ResponseEntity.ok(product)
    }

    private fun fetchCategories(restaurantId: String, locale: String): List<CategoryDto> {
        val catSql = """
            SELECT id, name, sort_order
            FROM category
            WHERE restaurant_id = CAST(:restaurantId AS UUID) AND active = TRUE
            ORDER BY sort_order ASC
        """

        return jdbc.query(catSql, mapOf("restaurantId" to restaurantId)) { rs, _ ->
            val catId = rs.getString("id")
            val catName = rs.getString("name")
            val sortOrder = rs.getInt("sort_order")
            val products = fetchProductsForCategory(catId, locale)

            CategoryDto(
                id = catId,
                name = catName,
                sortOrder = sortOrder,
                products = products
            )
        }
    }

    private fun fetchProductsForCategory(categoryId: String, locale: String): List<ProductDto> {
        val prodSql = """
            SELECT p.id, p.name, p.name_i18n, p.description, p.description_i18n, p.price_minor, p.available, s.code AS station_code,
                   p.allergens, p.image_path, p.image_version, p.image_alt
            FROM product p
            JOIN subcategory sub ON p.subcategory_id = sub.id
            JOIN station s ON p.station_id = s.id
            WHERE sub.category_id = CAST(:categoryId AS UUID)
              AND p.active = TRUE
            ORDER BY p.sort_order ASC
        """

        return jdbc.query(prodSql, mapOf("categoryId" to categoryId)) { rs, _ ->
            val nameJson = rs.getString("name_i18n")
            val resolvedName = resolveI18n(nameJson, locale) ?: rs.getString("name")

            val descJson = rs.getString("description_i18n")
            val resolvedDesc = resolveI18n(descJson, locale) ?: rs.getString("description")

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

            val productIdStr = rs.getString("id")
            val optionGroups = fetchOptionGroupsForProduct(productIdStr, locale)

            ProductDto(
                id = productIdStr,
                name = resolvedName,
                description = resolvedDesc,
                priceMinor = rs.getLong("price_minor"),
                available = rs.getBoolean("available"),
                station = rs.getString("station_code"),
                allergens = allergensArray.toList(),
                image = image,
                optionGroups = optionGroups
            )
        }
    }

    private fun fetchOptionGroupsForProduct(productId: String, locale: String): List<OptionGroupDto> {
        val sql = """
            SELECT og.id, og.name, og.name_i18n, og.selection_type, og.required, og.min_selections, og.max_selections, og.sort_order
            FROM option_group og
            JOIN product_option_group pog ON og.id = pog.group_id
            WHERE pog.product_id = CAST(:productId AS UUID)
              AND og.active = TRUE
            ORDER BY pog.sort_order ASC
        """

        return jdbc.query(sql, mapOf("productId" to productId)) { rs, _ ->
            val groupId = rs.getString("id")
            val nameJson = rs.getString("name_i18n")
            val resolvedName = resolveI18n(nameJson, locale) ?: rs.getString("name")
            val options = fetchOptionsForGroup(groupId, locale)

            OptionGroupDto(
                id = groupId,
                name = resolvedName,
                selectionType = rs.getString("selection_type"),
                required = rs.getBoolean("required"),
                minSelections = rs.getInt("min_selections"),
                maxSelections = rs.getInt("max_selections"),
                sortOrder = rs.getInt("sort_order"),
                options = options
            )
        }
    }

    private fun fetchOptionsForGroup(groupId: String, locale: String): List<OptionDto> {
        val sql = """
            SELECT id, name, name_i18n, price_delta_minor, available, is_default, sort_order
            FROM product_option
            WHERE group_id = CAST(:groupId AS UUID) AND active = TRUE
            ORDER BY sort_order ASC
        """

        return jdbc.query(sql, mapOf("groupId" to groupId)) { rs, _ ->
            val nameJson = rs.getString("name_i18n")
            val resolvedName = resolveI18n(nameJson, locale) ?: rs.getString("name")

            OptionDto(
                id = rs.getString("id"),
                name = resolvedName,
                priceDeltaMinor = rs.getLong("price_delta_minor"),
                available = rs.getBoolean("available"),
                isDefault = rs.getBoolean("is_default"),
                sortOrder = rs.getInt("sort_order")
            )
        }
    }

    private fun resolveI18n(jsonbStr: String?, locale: String): String? {
        if (jsonbStr.isNullOrBlank()) return null
        return try {
            val map = jsonParser.parseMap(jsonbStr)
            (map[locale] as? String) ?: (map["es-AR"] as? String)
        } catch (e: Exception) {
            null
        }
    }
}
