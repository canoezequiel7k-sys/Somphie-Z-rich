package com.somphie.catalog.infrastructure

import com.somphie.catalog.domain.ImageStorage
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Component
class FolderImageStorageAdapter : ImageStorage {

    private val mediaRoot: Path = Paths.get("media").toAbsolutePath().normalize()

    override fun load(path: String): ByteArray? {
        val normalizedPath = sanitizePath(path) ?: return null
        val targetFile = mediaRoot.resolve(normalizedPath).normalize()

        // Prevent path traversal outside mediaRoot
        if (!targetFile.startsWith(mediaRoot)) {
            return null
        }

        if (Files.exists(targetFile) && Files.isRegularFile(targetFile)) {
            return Files.readAllBytes(targetFile)
        }

        return null
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

    private fun sanitizePath(path: String): String? {
        val cleaned = path.trim().removePrefix("/")
        if (cleaned.contains("..")) return null
        return cleaned
    }
}
