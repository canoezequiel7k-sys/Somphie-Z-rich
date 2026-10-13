package com.somphie.floor.domain

import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID

/** Estados de sesión documentados; `CLOSED` es terminal y no se reabre. */
enum class TableSessionStatus { OPEN, CLOSED }

/** Motivos de cierre que define RN-SES-04, sin agregar variantes locales. */
enum class SessionCloseReason { SETTLED, EMPTY, EXPIRED_EMPTY, FORCED }

/** Roles de participantes definidos por el dominio de acceso a sesiones. */
enum class SessionParticipantRole { HOST, MEMBER }

/**
 * Participante inmutable contenido por la sesión; la autorización para revocarlo se valida en aplicación.
 */
@ConsistentCopyVisibility
data class SessionParticipant internal constructor(
    val id: UUID,
    val displayName: String,
    val role: SessionParticipantRole,
    val userId: UUID? = null,
    val revokedAt: Instant? = null,
) {
    init {
        require(displayName.length in 1..30) { "Participant display name must contain 1 to 30 characters" }
    }

    /** Devuelve un registro de revocación nuevo sin mutar el participante vigente. */
    fun revoke(now: Instant): SessionParticipant = SessionParticipant(id, displayName, role, userId, now)
}

/**
 * Hechos de pedidos y cuenta que el caso de uso calcula antes de pedir el cierre.
 * El dominio `floor` recibe datos resumidos y no depende de `orders` ni de `billing`.
 */
data class SessionClosureContext(
    val pendingOrderIds: List<UUID>,
    val activeItemIds: List<UUID>,
    val hasBillableCharges: Boolean,
    val balanceMinor: Long,
)

/**
 * Agregado puro de una visita. El almacenamiento, la unicidad de sesión por mesa y la generación del
 * JoinCode se resuelven fuera del dominio; aquí se protegen las transiciones y los invariantes propios.
 */
class TableSession private constructor(
    val id: UUID,
    val tableId: UUID,
    val status: TableSessionStatus,
    val openedAt: Instant,
    val closedAt: Instant?,
    val closeReason: SessionCloseReason?,
    val joinCode: JoinCode?,
    participants: List<SessionParticipant>,
    val lastActivityAt: Instant,
) {
    /** La copia defensiva evita que el llamador altere la membresía después de crear el agregado. */
    val participants: List<SessionParticipant> =
        Collections.unmodifiableList(ArrayList<SessionParticipant>(participants))

    init {
        // El código y los datos de cierre deben corresponder al estado documentado de la sesión.
        require((status == TableSessionStatus.OPEN) == (closedAt == null)) {
            "Open sessions cannot have a closedAt timestamp, and closed sessions must have one"
        }
        require((status == TableSessionStatus.OPEN) == (closeReason == null)) {
            "Open sessions cannot have a close reason, and closed sessions must have one"
        }
        require((status == TableSessionStatus.OPEN) == (joinCode != null)) {
            "Only open sessions can have a join code"
        }
        // La identidad de participantes y el rol único de HOST son invariantes del agregado.
        require(this.participants.map { it.id }.toSet().size == this.participants.size) {
            "Participant ids must be unique within the session"
        }
        require(this.participants.count { it.role == SessionParticipantRole.HOST } <= 1) {
            "A session can have at most one host"
        }
    }

    /** Registra actividad explícita; la aplicación la invoca solo para las acciones que cuentan como actividad. */
    fun recordActivity(now: Instant): TableSession {
        requireOpen()
        return copy(lastActivityAt = now)
    }

    /**
     * Agrega un participante y asigna `HOST` solo si la sesión todavía no tuvo uno.
     * Las validaciones de nombre y la autenticación del joinCode corresponden a la aplicación.
     */
    fun join(
        participantId: UUID,
        displayName: String,
        now: Instant,
        maxParticipants: Int,
        userId: UUID? = null,
    ): TableSession {
        requireOpen()
        require(maxParticipants > 0) { "maxParticipants must be positive" }
        require(participants.none { it.id == participantId }) { "Participant id must be unique within the session" }
        if (participants.count { it.revokedAt == null } >= maxParticipants) {
            throw DomainException(ErrorCode.SESSION_FULL, "Session $id has reached its participant limit")
        }
        val role = if (participants.none { it.role == SessionParticipantRole.HOST }) {
            SessionParticipantRole.HOST
        } else {
            SessionParticipantRole.MEMBER
        }
        val participant = SessionParticipant(participantId, displayName, role, userId)
        return copy(participants = participants + participant, lastActivityAt = now)
    }

    /** Rota el código de una sesión abierta; la fuente del nuevo valor queda fuera del agregado. */
    fun rotateJoinCode(newCode: JoinCode, now: Instant): TableSession {
        requireOpen()
        require(newCode != joinCode) { "Rotating a join code requires a different value" }
        return copy(joinCode = newCode, lastActivityAt = now)
    }

    /**
     * Revoca el acceso sin borrar el historial; `null` identifica al personal tras validar su permiso en aplicación.
     */
    fun revokeParticipant(actorParticipantId: UUID?, targetParticipantId: UUID, now: Instant): TableSession {
        requireOpen()
        val index = participants.indexOfFirst { it.id == targetParticipantId }
        if (index < 0) {
            throw DomainException(
                ErrorCode.PARTICIPANT_NOT_FOUND,
                "Participant $targetParticipantId does not belong to session $id",
            )
        }
        val target = participants[index]
        if (actorParticipantId == target.id && target.role == SessionParticipantRole.HOST) {
            throw DomainException(ErrorCode.HOST_CANNOT_BE_REVOKED, "A host cannot revoke themselves")
        }
        if (target.revokedAt != null) return this
        val updated = participants.toMutableList()
        updated[index] = target.revoke(now)
        return copy(participants = updated, lastActivityAt = now)
    }

    /** Cierra como `SETTLED` o `EMPTY` según los hechos de cuenta y pedidos ya calculados. */
    fun closeNormally(now: Instant, context: SessionClosureContext): TableSession {
        requireOpen()
        if (context.pendingOrderIds.isNotEmpty()) {
            throw DomainException(
                ErrorCode.SESSION_HAS_PENDING_ORDERS,
                "Session $id has pending orders",
                mapOf("orderIds" to context.pendingOrderIds),
            )
        }
        if (context.activeItemIds.isNotEmpty()) {
            throw DomainException(
                ErrorCode.SESSION_HAS_ACTIVE_ITEMS,
                "Session $id has active items",
                mapOf("itemIds" to context.activeItemIds),
            )
        }
        if (context.balanceMinor != 0L) {
            throw DomainException(
                ErrorCode.SESSION_BALANCE_PENDING,
                "Session $id has a non-zero balance",
                mapOf("balanceMinor" to context.balanceMinor),
            )
        }
        val reason = if (context.hasBillableCharges) SessionCloseReason.SETTLED else SessionCloseReason.EMPTY
        return closed(now, reason)
    }

    /**
     * Expira una visita vacía al cumplirse el TTL inclusivo, usando la última actividad guardada en el agregado.
     * Esta transición no programa ni persiste el cierre: eso corresponde al job de aplicación y su
     * actualización condicional.
     */
    fun expireEmpty(
        now: Instant,
        idleTtl: Duration,
        context: SessionClosureContext,
    ): TableSession {
        requireOpen()
        require(!idleTtl.isNegative && !idleTtl.isZero) { "idleTtl must be positive" }
        require(context.pendingOrderIds.isEmpty() && context.activeItemIds.isEmpty() && !context.hasBillableCharges) {
            "Only an empty session can expire"
        }
        require(!now.isBefore(lastActivityAt.plus(idleTtl))) { "Session is not due for empty expiry yet" }
        return closed(now, SessionCloseReason.EXPIRED_EMPTY)
    }

    /**
     * Marca el cierre forzado; el motivo obligatorio no se persiste en el agregado y debe conservarse en
     * el flujo de aplicación/auditoría junto con la cancelación de pedidos/ítems y la resolución del saldo.
     */
    fun forceClose(now: Instant, reason: String): TableSession {
        requireOpen()
        require(reason.isNotBlank()) { "A forced close requires a reason" }
        return closed(now, SessionCloseReason.FORCED)
    }

    /** Construye un agregado cerrado, invalida el código y conserva la sesión como historial. */
    private fun closed(now: Instant, reason: SessionCloseReason): TableSession =
        copy(status = TableSessionStatus.CLOSED, closedAt = now, closeReason = reason, joinCode = null)

    /** Rechaza mutaciones después del cierre mediante el código de catálogo ya definido. */
    private fun requireOpen() {
        if (status != TableSessionStatus.OPEN) {
            throw DomainException(ErrorCode.SESSION_CLOSED, "Session $id is closed")
        }
    }

    /** Reconstruye el valor inmutable con las listas copiadas de nuevo por el constructor. */
    private fun copy(
        status: TableSessionStatus = this.status,
        closedAt: Instant? = this.closedAt,
        closeReason: SessionCloseReason? = this.closeReason,
        joinCode: JoinCode? = this.joinCode,
        participants: List<SessionParticipant> = this.participants,
        lastActivityAt: Instant = this.lastActivityAt,
    ): TableSession = TableSession(
        id = id,
        tableId = tableId,
        status = status,
        openedAt = openedAt,
        closedAt = closedAt,
        closeReason = closeReason,
        joinCode = joinCode,
        participants = participants,
        lastActivityAt = lastActivityAt,
    )

    companion object {
        /**
         * Reconstruye una sesión guardada; el constructor valida sus invariantes y copia participantes.
         * La visibilidad interna evita agregar una vía de creación al contrato público del dominio.
         */
        internal fun fromStoredState(
            id: UUID,
            tableId: UUID,
            status: TableSessionStatus,
            openedAt: Instant,
            closedAt: Instant?,
            closeReason: SessionCloseReason?,
            joinCode: JoinCode?,
            participants: List<SessionParticipant>,
            lastActivityAt: Instant,
        ): TableSession = TableSession(
            id = id,
            tableId = tableId,
            status = status,
            openedAt = openedAt,
            closedAt = closedAt,
            closeReason = closeReason,
            joinCode = joinCode,
            participants = participants,
            lastActivityAt = lastActivityAt,
        )

        /** Abre una sesión QR cuyo primer participante ya es `HOST`. */
        fun openByQr(
            id: UUID,
            tableId: UUID,
            hostParticipantId: UUID,
            hostDisplayName: String,
            joinCode: JoinCode,
            now: Instant,
        ): TableSession = TableSession(
            id = id,
            tableId = tableId,
            status = TableSessionStatus.OPEN,
            openedAt = now,
            closedAt = null,
            closeReason = null,
            joinCode = joinCode,
            participants = listOf(
                SessionParticipant(hostParticipantId, hostDisplayName, SessionParticipantRole.HOST),
            ),
            lastActivityAt = now,
        )

        /** Abre una sesión manual sin `HOST`; el primer participante que se una tomará ese rol. */
        fun openManually(id: UUID, tableId: UUID, joinCode: JoinCode, now: Instant): TableSession =
            TableSession(
                id = id,
                tableId = tableId,
                status = TableSessionStatus.OPEN,
                openedAt = now,
                closedAt = null,
                closeReason = null,
                joinCode = joinCode,
                participants = emptyList(),
                lastActivityAt = now,
            )
    }
}
