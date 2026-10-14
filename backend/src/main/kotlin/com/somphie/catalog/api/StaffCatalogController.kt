package com.somphie.catalog.api

import com.somphie.audit.AuditService
import com.somphie.events.DomainEvent
import com.somphie.events.DomainEventType
import com.somphie.events.EventAggregateType
import com.somphie.events.EventOutboxService
import com.somphie.events.EventVersion
import com.somphie.identity.application.IdentityService
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class AvailabilityRequest(val available: Boolean)

data class AvailabilityResult(val id: String, val available: Boolean)

@RestController
class StaffCatalogController(
    private val jdbc: NamedParameterJdbcTemplate,
    private val identityService: IdentityService,
    private val auditService: AuditService,
    private val eventOutbox: EventOutboxService,
) {
    @PatchMapping("/staff/products/{productId}/availability")
    @Transactional
    fun setProductAvailability(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable productId: String,
        @RequestBody request: AvailabilityRequest,
    ): ResponseEntity<AvailabilityResult> {
        val productUuid = parseId(productId)
        val profile = authorize(jwt)
        val current = jdbc.query(
            """
            SELECT p.available
            FROM product p
            JOIN subcategory sub ON sub.id = p.subcategory_id
            JOIN category c ON c.id = sub.category_id
            WHERE p.id = :id AND c.restaurant_id = :restaurantId
            FOR UPDATE OF p
            """.trimIndent(),
            mapOf("id" to productUuid, "restaurantId" to profile.account.restaurantId),
        ) { result, _ -> result.getBoolean("available") }.firstOrNull()
            ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Product not found")

        if (current != request.available) {
            jdbc.update(
                "UPDATE product SET available = :available WHERE id = :id",
                mapOf("id" to productUuid, "available" to request.available),
            )
            recordCatalogChange(
                restaurantId = profile.account.restaurantId,
                actorId = profile.account.id,
                targetType = "PRODUCT",
                targetId = productUuid,
                before = current,
                after = request.available,
            )
        }
        return ResponseEntity.ok(AvailabilityResult(productUuid.toString(), request.available))
    }

    @PatchMapping("/staff/options/{optionId}/availability")
    @Transactional
    fun setOptionAvailability(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable optionId: String,
        @RequestBody request: AvailabilityRequest,
    ): ResponseEntity<AvailabilityResult> {
        val optionUuid = parseId(optionId)
        val profile = authorize(jwt)
        val current = jdbc.query(
            """
            SELECT po.available
            FROM product_option po
            JOIN option_group grp ON grp.id = po.group_id
            WHERE po.id = :id AND grp.restaurant_id = :restaurantId
            FOR UPDATE OF po
            """.trimIndent(),
            mapOf("id" to optionUuid, "restaurantId" to profile.account.restaurantId),
        ) { result, _ -> result.getBoolean("available") }.firstOrNull()
            ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Option not found")

        if (current != request.available) {
            jdbc.update(
                "UPDATE product_option SET available = :available WHERE id = :id",
                mapOf("id" to optionUuid, "available" to request.available),
            )
            recordCatalogChange(
                restaurantId = profile.account.restaurantId,
                actorId = profile.account.id,
                targetType = "OPTION",
                targetId = optionUuid,
                before = current,
                after = request.available,
            )
        }
        return ResponseEntity.ok(AvailabilityResult(optionUuid.toString(), request.available))
    }

    private fun authorize(jwt: Jwt) =
        identityService.me(jwt.subject, jwt.getClaim("rid"), jwt.getClaim("tv")).also { profile ->
            if ("availability.toggle" !in profile.permissions) {
                throw DomainException(ErrorCode.FORBIDDEN, "Availability permission is required")
            }
        }

    private fun recordCatalogChange(
        restaurantId: UUID,
        actorId: UUID,
        targetType: String,
        targetId: UUID,
        before: Boolean,
        after: Boolean,
    ) {
        val catalogVersion = requireNotNull(
            jdbc.queryForObject(
                """
                UPDATE restaurant
                SET catalog_version = catalog_version + 1
                WHERE id = :restaurantId
                RETURNING catalog_version
                """.trimIndent(),
                mapOf("restaurantId" to restaurantId),
                Long::class.javaObjectType,
            ),
        )
        auditService.record(
            restaurantId = restaurantId.toString(),
            actorType = "USER",
            actorId = actorId.toString(),
            action = "CATALOG_CHANGED",
            targetType = targetType,
            targetId = targetId.toString(),
            beforeSummary = """{"available":$before}""",
            afterSummary = """{"available":$after}""",
        )
        eventOutbox.append(
            DomainEvent(
                restaurantId = restaurantId,
                type = DomainEventType.CATALOG_UPDATED,
                aggregateType = EventAggregateType.CATALOG,
                aggregateId = restaurantId,
                version = EventVersion.Persisted(catalogVersion),
            ),
        )
    }

    private fun parseId(id: String): UUID = try {
        UUID.fromString(id)
    } catch (_: IllegalArgumentException) {
        throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Catalog resource not found")
    }
}
