package com.somphie.audit

import org.slf4j.MDC
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

//Marca la clase como un componente gestionado por Spring
@Service
class AuditService(
    //Esto hace que la inserciones sean livianas y comparta la misma transaccion SQL(@Transactional) de quien lo llama (RN-AUD-05)
    private val jdbc: NamedParameterJdbcTemplate
) {
    //Define datos necesarios para registrar un evento.
    fun record(
        //El ID del restaurante sobre el cual ocurre el evento
        restaurantId: String,
        //Quien realizo la accion
        actorType: String,
        //UUID del usuario o participante que la realizo
        actorId: String,
        //Codigo de la accion realizaca, por ejemplo: ORDER_CREATED, PARTICIPANT_REVOKED
        action: String,
        //Tipo de entidad afectada (ORDER, SESSION, CATALOG_ITEM)
        targetType: String,
        //UUID del recurso afectado
        targetId: String,
        //Motivo de la accion
        reason: String? = null,
        //Textos en formato JSON, que resumen el esrtado del recurso antes y despues de la operacion
        beforeSummary: String? = null,
        afterSummary: String? = null
    ) {
        //Recupera el identificador unico de la peticion HTTP/Proceso actual desde el MDC(Mapped Diagnostic Context del SLF4J). Esto permite correlacionar los log de la aplicacion con el regiustro de la base de datos
        val traceId = MDC.get("traceId") ?: UUID.randomUUID().toString()
        //UUID unico del registro de auditoria
        val eventId = UUID.randomUUID().toString()

        //Consulta SQL de inserccion.
        //Insercion directa en la tabla audit_event. Se aplica casting explicito a UUID y a JSONB para postgreSQL
        val sql = """
            INSERT INTO audit_event (id, restaurant_id, occurred_at, actor_type, actor_id, action, target_type, target_id, reason, before_summary, after_summary, trace_id)
            VALUES (CAST(:id AS UUID), CAST(:restaurantId AS UUID), :occurredAt, :actorType, CAST(:actorId AS UUID), :action, :targetType, CAST(:targetId AS UUID), :reason, CAST(:beforeSummary AS JSONB), CAST(:afterSummary AS JSONB), :traceId)
        """

        //Mapeo de tipos de actor(DOMICION <-> Almacenamiento DB)
        // RN-AUD usa nombres de dominio; V7 conserva sus valores de almacenamiento históricos.
        val persistedActorType = when (actorType) {
            "USER", "STAFF" -> "STAFF"
            "PARTICIPANT", "CUSTOMER" -> "CUSTOMER"
            "SYSTEM" -> "SYSTEM"
            else -> throw IllegalArgumentException("Unsupported audit actor type: $actorType")
        }

        //Inserta los datos con la fecha y hora exacta del servidor (Instant.now())
        jdbc.update(
            sql, mapOf(
                "id" to eventId,
                "restaurantId" to restaurantId,
                "occurredAt" to java.sql.Timestamp.from(Instant.now()),
                "actorType" to persistedActorType,
                "actorId" to actorId,
                "action" to action,
                "targetType" to targetType,
                "targetId" to targetId,
                "reason" to reason,
                "beforeSummary" to beforeSummary,
                "afterSummary" to afterSummary,
                "traceId" to traceId
            )
        )
    }
}
