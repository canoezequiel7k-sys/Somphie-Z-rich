package com.somphie.catalog.infrastructure

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class FolderImageStorageAdapterTest {
    private val adapter = FolderImageStorageAdapter()

    @Test
    fun `loads a regular image and returns null for missing or traversing paths`() {
        val mediaRoot = Path.of("media").toAbsolutePath().normalize()
        val rootExisted = Files.exists(mediaRoot)
        Files.createDirectories(mediaRoot)
        val fileName = "coverage-${UUID.randomUUID()}.png"
        val file = mediaRoot.resolve(fileName)
        val bytes = byteArrayOf(0, 1, 2, 127)
        try {
            Files.write(file, bytes)

            assertArrayEquals(bytes, adapter.load(fileName))
            assertNull(adapter.load("missing-${UUID.randomUUID()}.png"))
            assertNull(adapter.load("../outside.png"))
            assertNull(adapter.load(" "))
        } finally {
            Files.deleteIfExists(file)
            if (!rootExisted && Files.list(mediaRoot).use { !it.findAny().isPresent }) {
                Files.deleteIfExists(mediaRoot)
            }
        }
    }

    @Test
    fun `media type follows the supported image extension and has a safe fallback`() {
        assertEquals("image/webp", adapter.getMediaType("image.WEBP"))
        assertEquals("image/png", adapter.getMediaType("image.png"))
        assertEquals("image/jpeg", adapter.getMediaType("image.jpg"))
        assertEquals("image/jpeg", adapter.getMediaType("image.jpeg"))
        assertEquals("application/octet-stream", adapter.getMediaType("image.svg"))
    }
}
