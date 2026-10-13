package com.somphie.preparation.api

import com.somphie.identity.application.IdentityService
import com.somphie.preparation.application.ApplyItemAction
import com.somphie.preparation.application.GetAssignedStations
import com.somphie.preparation.application.GetStationQueue
import com.somphie.preparation.application.ItemAction
import com.somphie.preparation.application.PreparationAccess
import com.somphie.preparation.application.StationQueueAccess
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.idempotency.IdempotencyService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@RestController
class StaffPreparationController(
    private val getAssignedStations: GetAssignedStations,
    private val getStationQueue: GetStationQueue,
    private val identityService: IdentityService,
    private val applyItemAction: ApplyItemAction,
    private val idempotencyService: IdempotencyService,
) {
    @GetMapping("/staff/stations")
    fun listStations(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<Any> {
        val profile = identityService.me(jwt.subject, jwt.getClaim("rid"), jwt.getClaim("tv"))
        val access = StationQueueAccess(
            profile.account.restaurantId,
            profile.roles.toSet(),
            profile.permissions.toSet(),
            profile.stations.map { it.id }.toSet(),
        )
        return ResponseEntity.ok(getAssignedStations.execute(access))
    }

    @GetMapping("/staff/stations/{stationId}/queue")
    fun getStationQueue(
        @PathVariable stationId: String,
        @RequestParam(required = false) status: List<String>?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(required = false) cursor: String?,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Any> {
        val profile = identityService.me(jwt.subject, jwt.getClaim("rid"), jwt.getClaim("tv"))
        val access = StationQueueAccess(profile.account.restaurantId, profile.roles.toSet(), profile.permissions.toSet(), profile.stations.map { it.id }.toSet())
        return ResponseEntity.ok(getStationQueue.execute(access, stationId, status, limit, cursor))
    }

    @PostMapping("/staff/order-items/{itemId}/start")
    @Transactional
    fun startItem(
        @PathVariable itemId: String,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @AuthenticationPrincipal jwt: Jwt,
    ) = runItemAction(itemId, key, ItemAction.START, null, jwt)

    @PostMapping("/staff/order-items/{itemId}/ready")
    @Transactional
    fun readyItem(
        @PathVariable itemId: String,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @AuthenticationPrincipal jwt: Jwt,
    ) = runItemAction(itemId, key, ItemAction.READY, null, jwt)

    @PostMapping("/staff/order-items/{itemId}/deliver")
    @Transactional
    fun deliverItem(
        @PathVariable itemId: String,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @AuthenticationPrincipal jwt: Jwt,
    ) = runItemAction(itemId, key, ItemAction.DELIVER, null, jwt)

    @PostMapping("/staff/order-items/{itemId}/cancel")
    @Transactional
    fun cancelItem(
        @PathVariable itemId: String,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @RequestBody request: CancelItemRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ) = runItemAction(itemId, key, ItemAction.CANCEL, request.reason, jwt)

    @PostMapping("/staff/order-items/{itemId}/undo")
    @Transactional
    fun undoItem(
        @PathVariable itemId: String,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @AuthenticationPrincipal jwt: Jwt,
    ) = runItemAction(itemId, key, ItemAction.UNDO, null, jwt)

    @PostMapping("/staff/orders/{orderId}/deliver-ready")
    @Transactional
    fun deliverReady(
        @PathVariable orderId: String,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Map<String, Any>> {
        val access = preparationAccess(jwt)
        val uuid = parseUuid(orderId, "Order")
        return idempotencyService.executeIdempotent(
            "deliver-ready:${access.userId}:$orderId",
            key,
            "{}",
        ) { ResponseEntity.ok(applyItemAction.deliverReady(access, uuid)) }
    }

    private fun runItemAction(
        itemId: String,
        key: String?,
        action: ItemAction,
        reason: String?,
        jwt: Jwt,
    ): ResponseEntity<Map<String, Any>> {
        val access = preparationAccess(jwt)
        val uuid = parseUuid(itemId, "Order item")
        val request = "${action.name}:$reason"
        return idempotencyService.executeIdempotent(
            "${action.name.lowercase()}:${access.userId}:$itemId",
            key,
            request,
        ) { ResponseEntity.ok(applyItemAction.execute(access, uuid, action, reason)) }
    }

    private fun preparationAccess(jwt: Jwt): PreparationAccess {
        val profile = identityService.me(jwt.subject, jwt.getClaim("rid"), jwt.getClaim("tv"))
        return PreparationAccess(
            profile.account.restaurantId,
            profile.account.id,
            profile.account.displayName,
            profile.roles.toSet(),
            profile.permissions.toSet(),
            profile.stations.map { it.id }.toSet(),
        )
    }

    private fun parseUuid(value: String, resource: String): UUID = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "$resource not found")
    }

    data class CancelItemRequest(val reason: String)
}
