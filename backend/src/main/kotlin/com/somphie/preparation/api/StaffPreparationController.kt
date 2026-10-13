package com.somphie.preparation.api

import com.somphie.identity.application.IdentityService
import com.somphie.preparation.application.GetStationQueue
import com.somphie.preparation.application.StationQueueAccess
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class StaffPreparationController(
    private val getStationQueue: GetStationQueue,
    private val identityService: IdentityService,
) {
    @GetMapping("/staff/stations/{stationId}/queue")
    fun getStationQueue(
        @PathVariable stationId: String,
        @RequestParam(required = false) status: List<String>?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(required = false) cursor: String?,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Any> {
        val profile = identityService.me(jwt.subject, jwt.getClaim("rid"), jwt.getClaim("tv"))
        val access = StationQueueAccess(
            restaurantId = profile.account.restaurantId,
            roles = profile.roles.toSet(),
            permissions = profile.permissions.toSet(),
            stationIds = profile.stations.map { it.id }.toSet(),
        )
        return ResponseEntity.ok(getStationQueue.execute(access, stationId, status, limit, cursor))
    }
}
