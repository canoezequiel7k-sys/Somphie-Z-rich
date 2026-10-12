package com.somphie.shared.api

import com.fasterxml.jackson.annotation.JsonInclude
import java.net.URI

/** Respuesta compatible con el esquema `Problem` del contrato OpenAPI. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class ApiProblem(
    val type: URI,
    val title: String,
    val status: Int,
    val code: String,
    val detail: String? = null,
    val traceId: String? = null,
    val fieldErrors: List<ApiFieldError>? = null,
    val extra: Map<String, Any>? = null,
)

/** Detalla un campo inválido con los códigos que esperan los clientes del contrato. */
data class ApiFieldError(
    val field: String,
    val code: String,
    val message: String? = null,
)
