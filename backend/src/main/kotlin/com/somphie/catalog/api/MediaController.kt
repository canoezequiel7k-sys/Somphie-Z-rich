package com.somphie.catalog.api

import com.somphie.catalog.domain.ImageStorage
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.HandlerMapping

@RestController
class MediaController(
    private val imageStorage: ImageStorage
) {

    @GetMapping("/media/**")
    fun getMedia(request: HttpServletRequest): ResponseEntity<ByteArray> {
        val pathWithinMapping = request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE) as? String ?: ""
        val bestMatchingPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String ?: ""
        
        // Extract the wildcard part after /media/
        val path = extractPath(pathWithinMapping, bestMatchingPattern)

        val bytes = imageStorage.load(path) ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Image not found")
        val mediaType = imageStorage.getMediaType(path)

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(mediaType))
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
            .body(bytes)
    }

    private fun extractPath(pathWithinMapping: String, bestMatchingPattern: String): String {
        val patternPrefix = bestMatchingPattern.removeSuffix("**")
        return pathWithinMapping.removePrefix(patternPrefix).removePrefix("/")
    }
}
