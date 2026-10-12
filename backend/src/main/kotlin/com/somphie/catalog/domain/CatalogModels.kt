package com.somphie.catalog.domain

data class ImageDto(
    val path: String,
    val version: Int,
    val alt: String?
)

data class ProductDto(
    val id: String,
    val name: String,
    val description: String?,
    val priceMinor: Long,
    val available: Boolean,
    val station: String,
    val allergens: List<String>,
    val image: ImageDto?,
    val optionGroups: List<Any> = emptyList()
)

data class CategoryDto(
    val id: String,
    val name: String,
    val sortOrder: Int,
    val products: List<ProductDto>
)

data class MenuResponse(
    val catalogVersion: Long,
    val locale: String,
    val currency: String,
    val restaurantOpen: Boolean,
    val categories: List<CategoryDto>
)
