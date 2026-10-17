package com.somphie.catalog.infrastructure

import com.somphie.catalog.domain.ImageStorage
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

@Component
class FolderImageStorageAdapter : ImageStorage {

    private val mediaRoot: Path = Paths.get("media").toAbsolutePath().normalize()

    override fun load(path: String): ByteArray? {
        val normalizedPath = sanitizePath(path) ?: return null
        val targetFile = mediaRoot.resolve(normalizedPath).normalize()

        if (!targetFile.startsWith(mediaRoot) || !Files.exists(targetFile)) {
            return null
        }

        if (!Files.isDirectory(mediaRoot)) {
            return null
        }

        val realRoot = mediaRoot.toRealPath()
        val realFile = targetFile.toRealPath()
        if (!realFile.startsWith(realRoot) || !Files.isRegularFile(realFile)) {
            return null
        }

        val mediaType = getMediaType(normalizedPath.toString())
        if (!mediaType.startsWith("image/")) {
            return null
        }

        val bytes = Files.newInputStream(realFile).use { it.readNBytes(MAX_IMAGE_SIZE_BYTES + 1) }
        if (bytes.size > MAX_IMAGE_SIZE_BYTES || !matchesImageSignature(bytes, mediaType)) {
            return null
        }
        return bytes
    }

    override fun getMediaType(path: String): String {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }

    private fun sanitizePath(path: String): Path? {
        val cleaned = path.trim().replace('\\', '/')
        if (cleaned.isEmpty() || cleaned.startsWith('/') || ':' in cleaned || '\u0000' in cleaned) return null
        val segments = cleaned.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return try {
            Path.of(cleaned).takeUnless { it.isAbsolute }
        } catch (_: InvalidPathException) {
            null
        }
    }

    private fun matchesImageSignature(bytes: ByteArray, mediaType: String): Boolean = when (mediaType) {
        "image/png" -> bytes.size >= PNG_SIGNATURE.size && bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)
        "image/jpeg" -> bytes.size >= 3 && bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        "image/webp" -> bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
        else -> false
    }

    private companion object {
        const val MAX_IMAGE_SIZE_BYTES = 1_000_000
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
