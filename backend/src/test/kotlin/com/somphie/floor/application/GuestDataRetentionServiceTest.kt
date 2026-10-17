package com.somphie.floor.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class GuestDataRetentionServiceTest {
    private val jdbc = mock(NamedParameterJdbcTemplate::class.java)
    private val service = GuestDataRetentionService(
        jdbc,
        Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `anonymizes old guest names and deletes calls at the approved retention cutoffs`() {
        val statements = mutableListOf<String>()
        val parameters = mutableListOf<Map<String, *>>()
        given(jdbc.update(anyString(), anyMap<String, Any>()))
            .willAnswer { invocation ->
                statements += invocation.getArgument<String>(0)
                parameters += invocation.getArgument<Map<String, *>>(1)
                if (statements.size == 1) 3 else 2
            }

        val result = service.applyRetention()

        assertEquals(GuestDataRetentionResult(participantNamesAnonymized = 3, waiterCallsDeleted = 2), result)
        verify(jdbc, org.mockito.Mockito.times(2)).update(anyString(), anyMap<String, Any>())

        assertEquals(true, statements[0].contains("user_id IS NULL"))
        assertEquals(true, statements[0].contains("joined_at <= :cutoff"))
        assertEquals(true, statements[0].contains("display_name <> :anonymizedName"))
        assertEquals("Anónimo", parameters[0]["anonymizedName"])
        assertEquals(Timestamp.from(Instant.parse("2025-10-16T12:00:00Z")), parameters[0]["cutoff"])

        assertEquals(true, statements[1].contains("DELETE FROM waiter_call"))
        assertEquals(true, statements[1].contains("created_at <= :cutoff"))
        assertEquals(Timestamp.from(Instant.parse("2026-09-16T12:00:00Z")), parameters[1]["cutoff"])
    }
}
