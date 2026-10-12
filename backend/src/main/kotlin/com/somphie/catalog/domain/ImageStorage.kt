package com.somphie.catalog.domain

interface ImageStorage {
    fun load(path: String): ByteArray?
    fun getMediaType(path: String): String
}
