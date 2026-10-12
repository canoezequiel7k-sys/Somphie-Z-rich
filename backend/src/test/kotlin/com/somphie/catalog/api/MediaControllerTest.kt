package com.somphie.catalog.api

import com.somphie.catalog.domain.ImageStorage
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.springframework.http.HttpStatus
import org.springframework.web.servlet.HandlerMapping

class MediaControllerTest {

    private val imageStorage = mock(ImageStorage::class.java)
    private val controller = MediaController(imageStorage)

    @Test
    fun `getMedia returns image bytes when found`() {
        val request = mock(HttpServletRequest::class.java)
        given(request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE)).willReturn("/media/menu/flat-white.webp")
        given(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).willReturn("/media/**")

        val dummyBytes = byteArrayOf(1, 2, 3, 4)
        given(imageStorage.load("menu/flat-white.webp")).willReturn(dummyBytes)
        given(imageStorage.getMediaType("menu/flat-white.webp")).willReturn("image/webp")

        val response = controller.getMedia(request)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(dummyBytes, response.body)
    }

    @Test
    fun `getMedia throws DomainException when image not found`() {
        val request = mock(HttpServletRequest::class.java)
        given(request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE)).willReturn("/media/missing.webp")
        given(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).willReturn("/media/**")

        given(imageStorage.load(anyString())).willReturn(null)

        val exception = assertThrows<DomainException> {
            controller.getMedia(request)
        }
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.code)
    }
}
