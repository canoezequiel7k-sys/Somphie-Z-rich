package com.somphie.billing.api

import com.somphie.billing.application.BillQueryService
import com.somphie.billing.application.BillingService
import com.somphie.billing.application.CreatePaymentCommand
import com.somphie.billing.application.ReceiptPdfService
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@RestController
class BillController(
    private val billQueryService: BillQueryService,
    private val billingService: BillingService,
    private val receiptPdfService: ReceiptPdfService,
) {
    @GetMapping("/sessions/current/bill")
    fun currentBill(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
    ): Map<String, Any> = billQueryService.getCurrentBill(authorization)

    @PostMapping("/sessions/current/payments")
    @Transactional
    fun createPayment(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestHeader(name = "Idempotency-Key", required = false) key: String?,
        @RequestBody request: CreatePaymentRequest,
    ): ResponseEntity<Map<String, Any>> {
        val idempotencyKey = requireIdempotencyKey(key)
        return billingService.executeIdempotent(
            billingService.guestPaymentIdempotencyScope(authorization),
            idempotencyKey,
            "payment:create|${request.amountMinor}|${request.tipMinor}|${request.method}",
        ) {
            billingService.createGuestPayment(
                authorization,
                idempotencyKey,
                CreatePaymentCommand(request.amountMinor, request.tipMinor ?: 0, request.method),
            )
        }
    }

    @GetMapping("/sessions/current/payments")
    fun listPayments(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
    ): Map<String, Any> = mapOf("items" to billingService.listGuestPayments(authorization))

    @GetMapping("/sessions/current/receipt.pdf", produces = [MediaType.APPLICATION_PDF_VALUE])
    fun currentReceipt(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
    ): ResponseEntity<ByteArray> = ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(receiptPdfService.generate(authorization))

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

data class CreatePaymentRequest(
    val amountMinor: Long,
    val tipMinor: Long? = null,
    val method: String,
)
