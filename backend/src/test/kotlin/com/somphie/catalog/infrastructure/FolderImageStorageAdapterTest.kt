package com.somphie.catalog.infrastructure

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class FolderImageStorageAdapterTest {
    private val adapter = FolderImageStorageAdapter()
    private val mediaRoot = Path.of("media").toAbsolutePath().normalize()

    @Test
    fun `loads supported image bytes and returns null for missing or invalid paths`() {
        val rootExisted = Files.exists(mediaRoot)
        Files.createDirectories(mediaRoot)
        val fileName = "coverage-${UUID.randomUUID()}.png"
        val file = mediaRoot.resolve(fileName)
        val webp = mediaRoot.resolve("coverage-${UUID.randomUUID()}.webp")
        val bytes = pngBytes(32)
        val webpBytes = webpBytes()
        try {
            Files.write(file, bytes)
            Files.write(webp, webpBytes)

            assertArrayEquals(bytes, adapter.load(fileName))
            assertArrayEquals(webpBytes, adapter.load(webp.fileName.toString()))
            assertNull(adapter.load("missing-${UUID.randomUUID()}.png"))
            assertNull(adapter.load("../outside.png"))
            assertNull(adapter.load("sub\\..\\outside.png"))
            assertNull(adapter.load(" "))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(webp)
            removeMediaRootIfCreated(rootExisted)
        }
    }

    @Test
    fun `rejects unsupported formats mismatched signatures and files above one megabyte`() {
        val rootExisted = Files.exists(mediaRoot)
        Files.createDirectories(mediaRoot)
        val unsupported = mediaRoot.resolve("coverage-${UUID.randomUUID()}.svg")
        val mismatch = mediaRoot.resolve("coverage-${UUID.randomUUID()}.png")
        val tooLarge = mediaRoot.resolve("coverage-${UUID.randomUUID()}.jpg")
        try {
            Files.writeString(unsupported, "<svg/>")
            Files.writeString(mismatch, "not a png")
            Files.write(tooLarge, jpegBytes(1_000_001))

            assertNull(adapter.load(unsupported.fileName.toString()))
            assertNull(adapter.load(mismatch.fileName.toString()))
            assertNull(adapter.load(tooLarge.fileName.toString()))
        } finally {
            Files.deleteIfExists(unsupported)
            Files.deleteIfExists(mismatch)
            Files.deleteIfExists(tooLarge)
            removeMediaRootIfCreated(rootExisted)
        }
    }

    @Test
    fun `accepts supported images at exactly one megabyte`() {
        val rootExisted = Files.exists(mediaRoot)
        Files.createDirectories(mediaRoot)
        val file = mediaRoot.resolve("coverage-${UUID.randomUUID()}.jpg")
        val bytes = jpegBytes(1_000_000)
        try {
            Files.write(file, bytes)

            assertArrayEquals(bytes, adapter.load(file.fileName.toString()))
        } finally {
            Files.deleteIfExists(file)
            removeMediaRootIfCreated(rootExisted)
        }
    }

    @Test
    fun `rejects a symbolic link that resolves outside the media directory`() {
        val rootExisted = Files.exists(mediaRoot)
        Files.createDirectories(mediaRoot)
        val outside = Files.createTempFile("somphie-image-", ".png")
        val link = mediaRoot.resolve("coverage-${UUID.randomUUID()}.png")
        try {
            Files.write(outside, pngBytes(32))
            try {
                Files.createSymbolicLink(link, outside)
            } catch (exception: IOException) {
                assumeTrue(false, "The test environment does not permit symbolic links")
            } catch (exception: UnsupportedOperationException) {
                assumeTrue(false, "The test environment does not support symbolic links")
            } catch (exception: SecurityException) {
                assumeTrue(false, "The test environment does not permit symbolic links")
            }

            assertNull(adapter.load(link.fileName.toString()))
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(outside)
            removeMediaRootIfCreated(rootExisted)
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

    private fun pngBytes(size: Int): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            ByteArray(size - 8)

    private fun jpegBytes(size: Int): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(size - 3)

    private fun webpBytes(): ByteArray =
        "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray()

    private fun removeMediaRootIfCreated(rootExisted: Boolean) {
        if (!rootExisted && Files.list(mediaRoot).use { !it.findAny().isPresent }) {
            Files.deleteIfExists(mediaRoot)
        }
    }
}
