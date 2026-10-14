package com.somphie.billing.api

import com.somphie.billing.application.AdjustmentCommand
import com.somphie.billing.application.BillingService
import com.somphie.billing.application.CreateCashPaymentCommand
import com.somphie.identity.application.IdentityService
import com.somphie.identity.domain.StaffProfile
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class StaffBillingController(
    private val billingService: BillingService,
    private val identityService: IdentityService,
) {
    @PostMapping("/staff/sessions/{sessionId}/payments")
    @Transactional
    fun registerCashPayment(
        @PathVariable sessionId: String,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @RequestBody request: CreateCashPaymentRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Map<String, Any>> {
        val profile = authorize(jwt, "payment.register_cash")
        val session = parseUuid(sessionId)
        val idempotencyKey = requireIdempotencyKey(key)
        return billingService.executeIdempotent(
            billingService.cashPaymentIdempotencyScope(session, profile),
            idempotencyKey,
            "payment:cash:$session|$request",
        ) {
            billingService.createCashPayment(
                session,
                profile,
                idempotencyKey,
                CreateCashPaymentCommand(request.amountMinor, request.tipMinor ?: 0, request.payerLabel),
            )
        }
    }

    @PostMapping("/staff/payments/{paymentId}/void")
    @Transactional
    fun voidPayment(
        @PathVariable paymentId: String,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @RequestBody request: ReasonRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Map<String, Any>> {
        val profile = authorize(jwt, "payment.void")
        val payment = parseUuid(paymentId)
        val idempotencyKey = requireIdempotencyKey(key)
        return billingService.executeIdempotent(
            billingService.paymentVoidIdempotencyScope(payment, profile),
            idempotencyKey,
            "payment:void:$payment|$request",
        ) {
            billingService.voidPayment(payment, profile, idempotencyKey, request.reason)
        }
    }

    @PostMapping("/staff/sessions/{sessionId}/adjustments")
    @Transactional
    fun applyAdjustment(
        @PathVariable sessionId: String,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @RequestBody request: CreateAdjustmentRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Map<String, Any>> {
        val profile = authorize(jwt, "adjustment.apply", "adjustment.apply_any")
        val session = parseUuid(sessionId)
        val idempotencyKey = requireIdempotencyKey(key)
        return billingService.executeIdempotent(
            billingService.adjustmentCreateIdempotencyScope(session, profile),
            idempotencyKey,
            "adjustment:create:$session|$request",
        ) {
            val adjustment = billingService.applyAdjustment(
                session,
                profile,
                idempotencyKey,
                AdjustmentCommand(
                    request.kind,
                    request.percentBp,
                    request.amountMinor,
                    request.orderItemId,
                    request.reasonCode,
                    request.reasonText,
                ),
            )
            com.somphie.billing.application.BillingResult(201, adjustment)
        }
    }

    @GetMapping("/staff/sessions/{sessionId}/adjustments")
    fun listAdjustments(
        @PathVariable sessionId: String,
        @AuthenticationPrincipal jwt: Jwt,
    ): Map<String, Any> {
        val profile = authorize(jwt, "session.read")
        return mapOf("items" to billingService.listAdjustments(parseUuid(sessionId), profile))
    }

    @PostMapping("/staff/adjustments/{adjustmentId}/void")
    @Transactional
    fun voidAdjustment(
        @PathVariable adjustmentId: String,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @RequestBody request: ReasonRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Map<String, Any>> {
        val profile = authorize(jwt, "adjustment.void")
        val adjustment = parseUuid(adjustmentId)
        val idempotencyKey = requireIdempotencyKey(key)
        return billingService.executeIdempotent(
            billingService.adjustmentVoidIdempotencyScope(adjustment, profile),
            idempotencyKey,
            "adjustment:void:$adjustment|$request",
        ) {
            com.somphie.billing.application.BillingResult(
                200,
                billingService.voidAdjustment(adjustment, profile, idempotencyKey, request.reason),
            )
        }
    }

    private fun authorize(jwt: Jwt, vararg permissions: String): StaffProfile {
        val profile = identityService.me(jwt.subject, jwt.getClaim("rid"), jwt.getClaim("tv"))
        if (permissions.none { it in profile.permissions }) {
            throw DomainException(ErrorCode.FORBIDDEN, "Staff permission is required")
        }
        return profile
    }

    private fun parseUuid(value: String): UUID = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Resource not found")
    }

    private fun requireIdempotencyKey(key: String?): String {
        if (key.isNullOrBlank()) {
            throw DomainException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key header is required")
        }
        val parsed = try {
            UUID.fromString(key)
        } catch (_: IllegalArgumentException) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key must be a UUID v4")
        }
        if (parsed.version() != 4) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key must be a UUID v4")
        }
        return parsed.toString()
    }
}

data class CreateCashPaymentRequest(
    val amountMinor: Long,
    val tipMinor: Long? = null,
    val payerLabel: String? = null,
)

data class ReasonRequest(val reason: String)

data class CreateAdjustmentRequest(
    val kind: String,
    val percentBp: Int? = null,
    val amountMinor: Long? = null,
    val orderItemId: String? = null,
    val reasonCode: String,
    val reasonText: String? = null,
)
