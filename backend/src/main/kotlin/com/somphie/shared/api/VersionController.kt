package com.somphie.shared.api

import org.springframework.boot.info.BuildProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Devuelve únicamente las versiones de build y contrato definidas en el proyecto. */
@RestController
class VersionController(
    private val buildProperties: BuildProperties,
    @Value("\${somphie.contract-version}") private val contractVersion: String,
) {
    /** Mantiene la respuesta mínima pactada en OpenAPI para que los clientes validen conectividad. */
    @GetMapping("/version")
    fun getVersion(): VersionInfo = VersionInfo(
        build = requireNotNull(buildProperties.version) { "Gradle build metadata must include the version" },
        contractVersion = contractVersion,
    )
}

/** Representa la forma pública `VersionInfo` del contrato OpenAPI. */
data class VersionInfo(
    val build: String,
    val contractVersion: String,
)
