package com.somphie.floor.api

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
import java.time.Clock

class FloorControllerTest {

    private val jdbc = mock(NamedParameterJdbcTemplate::class.java)
    private val controller = FloorController(jdbc, mock(JoinSessionService::class.java), Clock.systemUTC())

    @Test
    fun `getPublicTable throws DomainException when table not found`() {
        given(jdbc.query(anyString(), anyMap<String, Any>(), org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper::class.java)))
            .willReturn(emptyList<Any>())

        val exception = assertThrows<DomainException> {
            controller.getPublicTable("INVALID-CODE")
        }
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.code)
    }
}
