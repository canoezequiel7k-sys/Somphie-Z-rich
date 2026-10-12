package com.somphie.floor.domain

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Prueba aperturas, participantes, expiración y cierres según estados §1 y RN-ACC/RN-SES. */
class TableSessionTest {
    private val now = Instant.parse("2026-10-12T10:00:00Z")
    private val tableId = UUID.randomUUID()
    private val sessionId = UUID.randomUUID()

    private fun code(value: String = "ABC234") = JoinCode.of(value)

    private fun manualSession() = TableSession.openManually(sessionId, tableId, code(), now)

    private fun emptyContext() = SessionClosureContext(emptyList(), emptyList(), false, 0)

    /** La fábrica interna de rehidratación permite probar la copia defensiva con una lista mutable externa. */
    @Test
    fun `participants are copied on construction and exposed as a read only list`() {
        val original = mutableListOf(
            SessionParticipant(UUID.randomUUID(), "Ana", SessionParticipantRole.HOST),
        )
        val session = TableSession.fromStoredState(
            id = sessionId,
            tableId = tableId,
            status = TableSessionStatus.OPEN,
            openedAt = now,
            closedAt = null,
            closeReason = null,
            joinCode = code(),
            participants = original,
            lastActivityAt = now,
        )
        val originalParticipants = session.participants.toList()

        original.clear()

        assertEquals(originalParticipants, session.participants)
        assertThrows(UnsupportedOperationException::class.java) {
            (session.participants as MutableList<SessionParticipant>).clear()
        }
        assertEquals(originalParticipants, session.participants)
    }

    /** El QR inicia con HOST y la apertura manual espera que se una el primer participante. */
    @Test
    fun `qr and manual openings apply their documented host rules`() {
        val hostId = UUID.randomUUID()
        val qr = TableSession.openByQr(sessionId, tableId, hostId, "Sofía", code(), now)
        val manual = manualSession()
        val first = manual.join(UUID.randomUUID(), "Ana", now, maxParticipants = 12)
        val second = first.join(UUID.randomUUID(), "Luis", now.plusSeconds(1), maxParticipants = 12)

        assertEquals(TableSessionStatus.OPEN, qr.status)
        assertEquals(SessionParticipantRole.HOST, qr.participants.single().role)
        assertTrue(manual.participants.isEmpty())
        assertEquals(SessionParticipantRole.HOST, first.participants.single().role)
        assertEquals(SessionParticipantRole.MEMBER, second.participants.last().role)
    }

    /** El límite cuenta participantes vigentes y una revocación libera su lugar sin borrar su historial. */
    @Test
    fun `joining respects participant limit and revocation preserves history`() {
        val hostId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val session = TableSession.openByQr(sessionId, tableId, hostId, "Host", code(), now)
            .join(memberId, "Miembro", now, maxParticipants = 2)

        val full = assertThrows(DomainException::class.java) {
            session.join(UUID.randomUUID(), "Tercero", now, maxParticipants = 2)
        }
        assertEquals(ErrorCode.SESSION_FULL, full.code)

        val revoked = session.revokeParticipant(hostId, memberId, now.plusSeconds(1))
        val joined = revoked.join(UUID.randomUUID(), "Nuevo", now.plusSeconds(2), maxParticipants = 2)

        assertEquals(now.plusSeconds(1), revoked.participants.last().revokedAt)
        assertEquals(3, joined.participants.size)
        assertEquals(2, joined.participants.count { it.revokedAt == null })
        assertEquals(SessionParticipantRole.MEMBER, joined.participants.last().role)
    }

    /** La regla prohíbe que el host se revoque a sí mismo; el personal puede revocarlo. */
    @Test
    fun `host cannot revoke themselves but staff can revoke the host`() {
        val hostId = UUID.randomUUID()
        val session = TableSession.openByQr(sessionId, tableId, hostId, "Host", code(), now)

        val error = assertThrows(DomainException::class.java) {
            session.revokeParticipant(hostId, hostId, now.plusSeconds(1))
        }
        val revoked = session.revokeParticipant(null, hostId, now.plusSeconds(2))

        assertEquals(ErrorCode.HOST_CANNOT_BE_REVOKED, error.code)
        assertEquals(now.plusSeconds(2), revoked.participants.single().revokedAt)
        assertEquals(session.participants.single().revokedAt, null)
    }

    /** Repetir una revocación conserva su primer instante y un id ajeno devuelve el error documentado. */
    @Test
    fun `revocation is idempotent and unknown participant reports catalog error`() {
        val hostId = UUID.randomUUID()
        val session = TableSession.openByQr(sessionId, tableId, hostId, "Host", code(), now)
        val revoked = session.revokeParticipant(null, hostId, now.plusSeconds(1))
        val repeated = revoked.revokeParticipant(null, hostId, now.plusSeconds(2))
        val missing = assertThrows(DomainException::class.java) {
            session.revokeParticipant(null, UUID.randomUUID(), now)
        }

        assertEquals(revoked.participants, repeated.participants)
        assertEquals(ErrorCode.PARTICIPANT_NOT_FOUND, missing.code)
    }

    /** Rotar reemplaza el valor y cerrar lo invalida junto con futuras mutaciones. */
    @Test
    fun `rotating and closing a session replace and then invalidate the join code`() {
        val session = manualSession()
        val rotated = session.rotateJoinCode(code("DEF567"), now.plusSeconds(1))
        val closed = rotated.closeNormally(now.plusSeconds(1), emptyContext())

        assertEquals(code("DEF567"), rotated.joinCode)
        assertEquals(now.plusSeconds(1), rotated.lastActivityAt)
        assertNull(closed.joinCode)
        assertEquals(SessionCloseReason.EMPTY, closed.closeReason)
        assertEquals(now.plusSeconds(1), closed.closedAt)
        assertEquals(ErrorCode.SESSION_CLOSED, assertThrows(DomainException::class.java) {
            closed.rotateJoinCode(code("QRS789"), now.plusSeconds(2))
        }.code)
    }

    /** El cierre normal distingue visita vacía de visita con cargos saldados. */
    @Test
    fun `normal close selects empty or settled reason from the billable-charge fact`() {
        val settled = manualSession().closeNormally(
            now.plusSeconds(1),
            SessionClosureContext(emptyList(), emptyList(), true, 0),
        )
        val empty = manualSession().closeNormally(now.plusSeconds(1), emptyContext())

        assertEquals(SessionCloseReason.SETTLED, settled.closeReason)
        assertEquals(SessionCloseReason.EMPTY, empty.closeReason)
        assertEquals(TableSessionStatus.CLOSED, settled.status)
        assertEquals(TableSessionStatus.CLOSED, empty.status)
    }

    /** Cada bloqueo de cierre devuelve su error y los datos, sin cerrar ni mutar el agregado original. */
    @Test
    fun `normal close rejects pending orders active items and nonzero balances without mutation`() {
        val session = manualSession()
        val orderId = UUID.randomUUID()
        val itemId = UUID.randomUUID()
        val contexts = listOf(
            SessionClosureContext(listOf(orderId), emptyList(), false, 0) to ErrorCode.SESSION_HAS_PENDING_ORDERS,
            SessionClosureContext(emptyList(), listOf(itemId), true, 0) to ErrorCode.SESSION_HAS_ACTIVE_ITEMS,
            SessionClosureContext(emptyList(), emptyList(), false, 1) to ErrorCode.SESSION_BALANCE_PENDING,
            SessionClosureContext(emptyList(), emptyList(), true, -1) to ErrorCode.SESSION_BALANCE_PENDING,
        )

        contexts.forEach { (context, expectedCode) ->
            val error = assertThrows(DomainException::class.java) {
                session.closeNormally(now.plusSeconds(1), context)
            }
            assertEquals(expectedCode, error.code)
            assertEquals(TableSessionStatus.OPEN, session.status)
            assertEquals(code(), session.joinCode)
        }
    }

    /** La visita vacía expira al alcanzar exactamente el TTL y no antes. */
    @Test
    fun `empty session expires at the idle ttl boundary but not before`() {
        val session = manualSession()
        val ttl = Duration.ofMinutes(30)

        assertThrows(IllegalArgumentException::class.java) {
            session.expireEmpty(now.plus(ttl).minusNanos(1), ttl, emptyContext())
        }
        val expired = session.expireEmpty(now.plus(ttl), ttl, emptyContext())

        assertEquals(SessionCloseReason.EXPIRED_EMPTY, expired.closeReason)
        assertNull(expired.joinCode)
    }

    /** No se puede expirar como vacía una visita que tenga pedidos pendientes o cargos facturables. */
    @Test
    fun `only an empty session is eligible for idle expiry`() {
        val session = manualSession()
        val context = SessionClosureContext(listOf(UUID.randomUUID()), emptyList(), false, 0)

        assertThrows(IllegalArgumentException::class.java) {
            session.expireEmpty(now.plus(Duration.ofHours(1)), Duration.ofMinutes(30), context)
        }
        assertEquals(TableSessionStatus.OPEN, session.status)
    }

    /** El cierre forzado exige motivo y marca el estado; sus efectos sobre pedidos/saldo quedan al orquestador. */
    @Test
    fun `forced close requires a reason and is terminal`() {
        val session = manualSession()

        assertThrows(IllegalArgumentException::class.java) { session.forceClose(now, " ") }
        val closed = session.forceClose(now.plusSeconds(1), "Pedido solicitado")

        assertEquals(SessionCloseReason.FORCED, closed.closeReason)
        assertEquals(TableSessionStatus.CLOSED, closed.status)
        assertNull(closed.joinCode)
        assertEquals(ErrorCode.SESSION_CLOSED, assertThrows(DomainException::class.java) {
            closed.recordActivity(now.plusSeconds(2))
        }.code)
    }

    /** La actividad explícita refresca el instante que la aplicación usará para el job de sesión vacía. */
    @Test
    fun `recording activity returns a new session with the supplied instant`() {
        val session = manualSession()
        val activityAt = now.plusSeconds(10)

        val updated = session.recordActivity(activityAt)

        assertEquals(activityAt, updated.lastActivityAt)
        assertEquals(now, session.lastActivityAt)
    }
}
