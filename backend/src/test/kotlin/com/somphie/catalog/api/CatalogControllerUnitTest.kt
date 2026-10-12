package com.somphie.catalog.api

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class CatalogControllerUnitTest {

    private val jdbc = mock(NamedParameterJdbcTemplate::class.java)
    private val controller = CatalogController(jdbc)

    @Test
    fun `getMenu throws DomainException when restaurant not found`() {
        given(jdbc.queryForObject(anyString(), anyMap<String, Any>(), eq(Int::class.java))).willReturn(0)

        val exception = assertThrows<DomainException> {
            controller.getMenu("00000000-0000-0000-0000-000000000000", null, null, null)
        }
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.code)
    }
}
