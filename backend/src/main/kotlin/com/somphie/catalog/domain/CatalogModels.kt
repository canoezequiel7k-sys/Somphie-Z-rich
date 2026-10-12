package com.somphie.catalog.domain

data class ImageDto(
    val path: String,
    val version: Int,
    val alt: String?
)

data class OptionDto(
    val id: String,
    val name: String,
    val priceDeltaMinor: Long,
    val available: Boolean,
    val isDefault: Boolean,
    val sortOrder: Int
)

data class OptionGroupDto(
    val id: String,
    val name: String,
    val selectionType: String,
    val required: Boolean,
    val minSelections: Int,
    val maxSelections: Int,
    val sortOrder: Int,
    val options: List<OptionDto>
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
    val optionGroups: List<OptionGroupDto> = emptyList()
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
