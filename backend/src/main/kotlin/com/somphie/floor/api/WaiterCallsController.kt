package com.somphie.floor.api

import com.somphie.floor.application.WaiterCallService
import com.somphie.floor.domain.CreateWaiterCallRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class WaiterCallsController(
    private val waiterCallService: WaiterCallService,
) {
    @PostMapping("/sessions/current/waiter-calls")
    fun create(
        @Valid @RequestBody request: CreateWaiterCallRequest,
        @RequestHeader(name = "Authorization", required = false) authorization: String?,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<Map<String, Any>> =
        waiterCallService.create(authorization, idempotencyKey, request)

    @GetMapping("/sessions/current/waiter-calls")
    fun list(
        @RequestHeader(name = "Authorization", required = false) authorization: String?,
    ): Map<String, Any> = waiterCallService.listForGuest(authorization)
}
