package com.somphie.shared.idempotency

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class IdempotencyRetentionServiceTest {
    private val jdbc = mock(NamedParameterJdbcTemplate::class.java)
    private val service = IdempotencyRetentionService(
        jdbc,
        Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `deletes records at or older than the 24 hour retention boundary`() {
        var statement = ""
        var parameters: Map<String, *> = emptyMap<String, Any>()
        given(jdbc.update(anyString(), anyMap<String, Any>()))
            .willAnswer { invocation ->
                statement = invocation.getArgument<String>(0)
                parameters = invocation.getArgument<Map<String, *>>(1)
                2
            }

        val deleted = service.deleteExpiredRecords()

        assertEquals(2, deleted)
        assertTrue(statement.contains("DELETE FROM idempotency_record"))
        assertTrue(statement.contains("created_at <= :cutoff"))
        assertEquals(Timestamp.from(Instant.parse("2026-10-15T12:00:00Z")), parameters["cutoff"])
    }
}
