package com.somphie.billing.application

import com.somphie.audit.AuditService
import com.somphie.billing.domain.AdjustmentContext
import com.somphie.billing.domain.AdjustmentKind
import com.somphie.billing.domain.AdjustmentRequest
import com.somphie.billing.domain.AdjustmentRules
import com.somphie.billing.domain.CourtesyRules
import com.somphie.billing.domain.Payment
import com.somphie.billing.domain.PaymentContext
import com.somphie.billing.domain.PaymentKind
import com.somphie.billing.domain.PaymentMethod
import com.somphie.billing.domain.PaymentRules
import com.somphie.billing.domain.PaymentStatus
import com.somphie.billing.domain.VoidRules
import com.somphie.billing.domain.RefundRules
import com.somphie.floor.application.ForcedSessionRefundPort
import com.somphie.identity.domain.StaffProfile
import com.somphie.orders.application.OrderChargesPort
import com.somphie.preparation.application.CourtesyCancellationPort
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.domain.Money
import com.somphie.shared.idempotency.IdempotencyService
import org.slf4j.MDC
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID

data class BillingResult(val status: Int, val body: Map<String, Any>)

data class CreatePaymentCommand(val amountMinor: Long, val tipMinor: Long, val method: String)
data class CreateCashPaymentCommand(val amountMinor: Long, val tipMinor: Long, val payerLabel: String?)
data class CreateRefundCommand(val amountMinor: Long, val reason: String)
data class AdjustmentCommand(
    val kind: String,
    val percentBp: Int?,
    val amountMinor: Long?,
    val orderItemId: String?,
    val reasonCode: String,
    val reasonText: String?,
)

@Service
class BillingService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val billQuery: BillQueryService,
    private val orderCharges: OrderChargesPort,
    private val idempotencyService: IdempotencyService,
    private val paymentProvider: SimulatedPaymentProvider,
    private val audit: AuditService,
    private val clock: Clock,
) : CourtesyCancellationPort, ForcedSessionRefundPort {

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override fun refundNegativeBalance(
        sessionId: UUID,
        restaurantId: UUID,
        actorId: UUID,
        reason: String,
        idempotencyKey: String,
    ): Long {
        lockOpenSession(sessionId, restaurantId)
        val bill = billQuery.billForSession(sessionId, restaurantId)
        val balanceMinor = (bill.getValue("balanceMinor") as Number).toLong()
        if (balanceMinor >= 0) return balanceMinor

        val amountMinor = Math.negateExact(balanceMinor)
        val netPaid = Money.of((bill.getValue("paidMinor") as Number).toLong())
        val paymentKey = UUID.nameUUIDFromBytes(
            "forced-session-close:$sessionId:$idempotencyKey".toByteArray(Charsets.UTF_8),
        ).toString()
        requireMovementKeyAvailable(sessionId, paymentKey)
        val payment = Payment.refund(
            id = UUID.randomUUID(),
            sessionId = sessionId,
            method = PaymentMethod.SIMULATED_ONLINE,
            amount = Money.of(amountMinor),
            netPaid = netPaid,
            reason = reason,
            idempotencyKey = paymentKey,
        )
        insertPayment(payment, restaurantId)
        audit.record(
            restaurantId.toString(),
            "USER",
            actorId.toString(),
            "REFUND_CREATED",
            "PAYMENT",
            payment.id.toString(),
            reason = reason,
            afterSummary = """{"amountMinor":${payment.amount.minor},"source":"FORCED_SESSION_CLOSE","label":"automático por cierre forzado"}""",
        )
        return (billQuery.billForSession(sessionId, restaurantId).getValue("balanceMinor") as Number).toLong()
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun createGuestPayment(authorization: String?, idempotencyKey: String, command: CreatePaymentCommand): BillingResult {
        val access = guestAccess(authorization)
        if (access.revoked) throw DomainException(ErrorCode.SESSION_ACCESS_REVOKED, "Guest access was revoked")
        lockOpenSession(access.sessionId, access.restaurantId)
        requireMovementKeyAvailable(access.sessionId, idempotencyKey)
        validatePaymentRequest(command.amountMinor, command.tipMinor)
        if (command.method != PaymentMethod.SIMULATED_ONLINE.name) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid payment method")
        }
        val bill = billQuery.billForSession(access.sessionId, access.restaurantId)
        val context = paymentContext(access.restaurantId, bill)
        val amount = Money.of(command.amountMinor)
        val tip = Money.of(command.tipMinor)
        PaymentRules.validate(amount, tip, context)
        val approved = amount.isZero || paymentProvider.approve(amount.minor)
        val status = if (approved) PaymentStatus.SUCCEEDED else PaymentStatus.FAILED
        val payment = Payment.record(
            id = UUID.randomUUID(),
            sessionId = access.sessionId,
            method = PaymentMethod.SIMULATED_ONLINE,
            status = status,
            amount = amount,
            tip = if (approved) tip else Money.ZERO,
            payerParticipantId = access.participantId,
            idempotencyKey = idempotencyKey,
        )
        insertPayment(payment, access.restaurantId)
        audit.record(
            restaurantId = access.restaurantId.toString(),
            actorType = "PARTICIPANT",
            actorId = access.participantId.toString(),
            action = if (approved) "PAYMENT_CREATED" else "PAYMENT_FAILED",
            targetType = "PAYMENT",
            targetId = payment.id.toString(),
            afterSummary = if (approved) {
                """{"amountMinor":${payment.amount.minor},"tipMinor":${payment.tip.minor},"method":"${payment.method.name}"}"""
            } else {
                """{"amountMinor":${payment.amount.minor}}"""
            },
        )
        if (!approved) return declinedResult(payment.id)
        return paymentResult(payment.id, access.sessionId, access.restaurantId, 201)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun createCashPayment(
        sessionId: UUID,
        profile: StaffProfile,
        idempotencyKey: String,
        command: CreateCashPaymentCommand,
    ): BillingResult {
        requirePermission(profile, "payment.register_cash")
        lockOpenSession(sessionId, profile.account.restaurantId)
        requireMovementKeyAvailable(sessionId, idempotencyKey)
        validatePaymentRequest(command.amountMinor, command.tipMinor)
        val bill = billQuery.billForSession(sessionId, profile.account.restaurantId)
        PaymentRules.validate(
            Money.of(command.amountMinor),
            Money.of(command.tipMinor),
            paymentContext(profile.account.restaurantId, bill),
        )
        val payment = Payment.record(
            id = UUID.randomUUID(),
            sessionId = sessionId,
            method = PaymentMethod.CASH_COUNTER,
            status = PaymentStatus.SUCCEEDED,
            amount = Money.of(command.amountMinor),
            tip = Money.of(command.tipMinor),
            payerLabel = command.payerLabel,
            idempotencyKey = idempotencyKey,
        )
        insertPayment(payment, profile.account.restaurantId)
        audit.record(
            profile.account.restaurantId.toString(), "USER", profile.account.id.toString(),
            "PAYMENT_CREATED", "PAYMENT", payment.id.toString(),
            afterSummary = """{"amountMinor":${payment.amount.minor},"tipMinor":${payment.tip.minor},"method":"${payment.method.name}"}""",
        )
        return paymentResult(payment.id, sessionId, profile.account.restaurantId, 201)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun voidPayment(
        paymentId: UUID,
        profile: StaffProfile,
        idempotencyKey: String,
        reason: String,
    ): BillingResult {
        requirePermission(profile, "payment.void")
        requireReason(reason)
        val sessionId = findPaymentSession(paymentId, profile.account.restaurantId)
        lockOpenSession(sessionId, profile.account.restaurantId)
        val row = jdbc.query(
            """
            SELECT kind, method, status, amount_minor, tip_minor, payer_participant_id, payer_label,
                   reason, idempotency_key, voided_at
            FROM payment
            WHERE id = :paymentId AND session_id = :sessionId AND restaurant_id = :restaurantId
            FOR UPDATE
            """.trimIndent(),
            mapOf("paymentId" to paymentId, "sessionId" to sessionId, "restaurantId" to profile.account.restaurantId),
        ) { rs, _ ->
            Payment.restore(
                id = paymentId,
                sessionId = sessionId,
                kind = PaymentKind.valueOf(rs.getString("kind")),
                method = PaymentMethod.valueOf(rs.getString("method")),
                status = PaymentStatus.valueOf(rs.getString("status")),
                amount = Money.of(rs.getLong("amount_minor")),
                tip = Money.of(rs.getLong("tip_minor")),
                payerParticipantId = rs.getObject("payer_participant_id", UUID::class.java),
                payerLabel = rs.getString("payer_label"),
                reason = rs.getString("reason"),
                idempotencyKey = rs.getString("idempotency_key"),
                voidedAt = rs.getTimestamp("voided_at")?.toInstant(),
            )
        }.firstOrNull() ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Payment not found")
        val voided = row.void(reason, clock.instant())
        jdbc.update(
            """
            UPDATE payment SET status = 'VOIDED', voided_at = :voidedAt
            WHERE id = :paymentId AND session_id = :sessionId AND restaurant_id = :restaurantId
              AND status = 'SUCCEEDED'
            """.trimIndent(),
            mapOf(
                "voidedAt" to java.sql.Timestamp.from(checkNotNull(voided.voidedAt)),
                "paymentId" to paymentId,
                "sessionId" to sessionId,
                "restaurantId" to profile.account.restaurantId,
            ),
        )
        audit.record(
            profile.account.restaurantId.toString(), "USER", profile.account.id.toString(),
            "PAYMENT_VOIDED", "PAYMENT", paymentId.toString(), reason = reason,
        )
        return paymentResult(paymentId, sessionId, profile.account.restaurantId, 200)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun createRefund(
        sessionId: UUID,
        profile: StaffProfile,
        idempotencyKey: String,
        command: CreateRefundCommand,
    ): BillingResult {
        requirePermission(profile, "payment.refund")
        requireReason(command.reason)
        lockOpenSession(sessionId, profile.account.restaurantId)
        requireMovementKeyAvailable(sessionId, idempotencyKey)
        val bill = billQuery.billForSession(sessionId, profile.account.restaurantId)
        val netPaid = Money.of((bill.getValue("paidMinor") as Number).toLong())
        val amount = Money.of(command.amountMinor)
        RefundRules.validate(amount, netPaid)
        val payment = Payment.refund(
            id = UUID.randomUUID(),
            sessionId = sessionId,
            method = PaymentMethod.SIMULATED_ONLINE,
            amount = amount,
            netPaid = netPaid,
            reason = command.reason,
            idempotencyKey = idempotencyKey,
        )
        insertPayment(payment, profile.account.restaurantId)
        audit.record(
            profile.account.restaurantId.toString(), "USER", profile.account.id.toString(),
            "REFUND_CREATED", "PAYMENT", payment.id.toString(), reason = command.reason,
            afterSummary = """{"amountMinor":${payment.amount.minor}}""",
        )
        return paymentResult(payment.id, sessionId, profile.account.restaurantId, 201)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun applyAdjustment(
        sessionId: UUID,
        profile: StaffProfile,
        idempotencyKey: String,
        command: AdjustmentCommand,
    ): Map<String, Any> {
        requirePermission(profile, "adjustment.apply", "adjustment.apply_any")
        requireReasonCode(command.reasonCode)
        if (command.reasonText != null && command.reasonText.length > 140) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "reasonText is too long")
        }
        lockOpenSession(sessionId, profile.account.restaurantId)
        requireMovementKeyAvailable(sessionId, idempotencyKey)
        val bill = billQuery.billForSession(sessionId, profile.account.restaurantId)
        val kind = try {
            AdjustmentKind.valueOf(command.kind)
        } catch (_: IllegalArgumentException) {
            throw DomainException(ErrorCode.ADJUSTMENT_INVALID, "Invalid adjustment kind")
        }
        val itemId = command.orderItemId?.let(::parseUuid)
        val itemLineTotal = if (kind == AdjustmentKind.COURTESY_ITEM) {
            val facts = orderCharges.courtesyItems(sessionId, profile.account.restaurantId)
            val item = facts.firstOrNull { it.id == itemId }
                ?: throw DomainException(ErrorCode.ADJUSTMENT_ITEM_INVALID, "Courtesy item is not in this session")
            val activeCourtesyIds = jdbc.query(
                """
                SELECT order_item_id FROM bill_adjustment
                WHERE session_id = :sessionId AND restaurant_id = :restaurantId
                  AND kind = 'COURTESY_ITEM' AND status = 'APPLIED'
                """.trimIndent(),
                mapOf("sessionId" to sessionId, "restaurantId" to profile.account.restaurantId),
            ) { rs, _ -> rs.getObject("order_item_id", UUID::class.java) }.toSet()
            CourtesyRules.lineTotalFor(
                CourtesyRules.ItemFacts(
                    item.id,
                    Money.of(item.lineTotalMinor),
                    item.accepted,
                    item.cancelled,
                ),
                activeCourtesyIds,
            )
        } else {
            if (itemId != null) throw DomainException(ErrorCode.ADJUSTMENT_INVALID, "orderItemId is only valid for courtesy")
            null
        }
        if (kind == AdjustmentKind.DISCOUNT_PERCENT && (command.amountMinor != null || itemId != null) ||
            kind in setOf(AdjustmentKind.DISCOUNT_AMOUNT, AdjustmentKind.MANUAL_ADJUSTMENT) &&
            (command.percentBp != null || itemId != null) ||
            kind == AdjustmentKind.COURTESY_ITEM &&
            (command.percentBp != null || command.amountMinor != null)
        ) {
            throw DomainException(ErrorCode.ADJUSTMENT_INVALID, "Adjustment fields do not match kind")
        }
        val request = AdjustmentRequest(
            kind = kind,
            percentBp = command.percentBp,
            amount = command.amountMinor?.let { nonNegativeMoney(it) },
            itemLineTotal = itemLineTotal,
        )
        val settings = settings(profile.account.restaurantId)
        val appliedAdjustments = Money.of((bill.getValue("adjustmentsMinor") as Number).toLong())
        val grossCharges = Money.of((bill.getValue("chargesMinor") as Number).toLong())
        val balance = com.somphie.billing.domain.Balance(
            (bill.getValue("balanceMinor") as Number).toLong(),
        )
        val staffApplied = jdbc.query(
            """
            SELECT COALESCE(SUM(amount_minor), 0)
            FROM bill_adjustment
            WHERE session_id = :sessionId AND restaurant_id = :restaurantId
              AND created_by = :userId AND status = 'APPLIED' AND NOT limit_exempt
            """.trimIndent(),
            mapOf(
                "sessionId" to sessionId,
                "restaurantId" to profile.account.restaurantId,
                "userId" to profile.account.id,
            ),
        ) { rs, _ -> rs.getLong(1) }.first()
        val canApplyAny = "adjustment.apply_any" in profile.permissions
        val amount = AdjustmentRules.validate(
            request,
            AdjustmentContext(
                grossCharges = grossCharges,
                appliedAdjustments = appliedAdjustments,
                balance = balance,
                staffAppliedSoFar = Money.of(staffApplied),
                maxStaffDiscountPercent = settings.maxStaffDiscountPercent,
                maxStaffAbsolute = settings.maxStaffAbsolute?.let(Money::of),
                canApplyAny = canApplyAny,
            ),
        )
        val id = UUID.randomUUID()
        val now = clock.instant()
        jdbc.update(
            """
            INSERT INTO bill_adjustment
                (id, restaurant_id, session_id, kind, status, amount_minor, percent_bp, order_item_id,
                 reason_code, reason_text, created_by, limit_exempt, created_at, idempotency_key)
            VALUES
                (:id, :restaurantId, :sessionId, :kind, 'APPLIED', :amountMinor, :percentBp, :orderItemId,
                 :reasonCode, :reasonText, :createdBy, :limitExempt, :createdAt, :idempotencyKey)
            """.trimIndent(),
            mapOf(
                "id" to id,
                "restaurantId" to profile.account.restaurantId,
                "sessionId" to sessionId,
                "kind" to kind.name,
                "amountMinor" to amount.minor,
                "percentBp" to command.percentBp,
                "orderItemId" to itemId,
                "reasonCode" to command.reasonCode,
                "reasonText" to command.reasonText,
                "createdBy" to profile.account.id,
                "limitExempt" to canApplyAny,
                "createdAt" to java.sql.Timestamp.from(now),
                "idempotencyKey" to idempotencyKey,
            ),
        )
        audit.record(
            profile.account.restaurantId.toString(), "USER", profile.account.id.toString(),
            "ADJUSTMENT_APPLIED", "ADJUSTMENT", id.toString(), reason = command.reasonCode,
            afterSummary = """{"kind":"${kind.name}","amountMinor":${amount.minor}}""",
        )
        return adjustmentView(id, profile.account.restaurantId)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun listAdjustments(sessionId: UUID, profile: StaffProfile): List<Map<String, Any>> {
        requirePermission(profile, "session.read")
        lockSession(sessionId, profile.account.restaurantId)
        return adjustmentViews(sessionId, profile.account.restaurantId)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun voidAdjustment(
        adjustmentId: UUID,
        profile: StaffProfile,
        idempotencyKey: String,
        reason: String,
    ): Map<String, Any> {
        requirePermission(profile, "adjustment.void")
        requireReason(reason)
        val sessionId = findAdjustmentSession(adjustmentId, profile.account.restaurantId)
        lockOpenSession(sessionId, profile.account.restaurantId)
        val adjustment = jdbc.query(
            """
            SELECT status, created_at, created_by
            FROM bill_adjustment
            WHERE id = :id AND session_id = :sessionId AND restaurant_id = :restaurantId
            FOR UPDATE
            """.trimIndent(),
            mapOf("id" to adjustmentId, "sessionId" to sessionId, "restaurantId" to profile.account.restaurantId),
        ) { rs, _ ->
            AdjustmentToVoid(
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("created_by", UUID::class.java),
            )
        }.firstOrNull() ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Adjustment not found")
        VoidRules.requireAdjustmentVoidable(
            status = com.somphie.billing.domain.AdjustmentStatus.valueOf(adjustment.status),
            appliedAt = adjustment.appliedAt,
            now = clock.instant(),
            canVoidAny = "OWNER" in profile.roles,
            isOwn = adjustment.appliedBy == profile.account.id,
            window = Duration.ofMinutes(10),
        )
        val now = clock.instant()
        jdbc.update(
            """
            UPDATE bill_adjustment
            SET status = 'VOIDED', voided_by = :voidedBy, voided_at = :voidedAt, void_reason = :reason
            WHERE id = :id AND session_id = :sessionId AND restaurant_id = :restaurantId AND status = 'APPLIED'
            """.trimIndent(),
            mapOf(
                "voidedAt" to java.sql.Timestamp.from(now),
                "voidedBy" to profile.account.id,
                "reason" to reason,
                "id" to adjustmentId,
                "sessionId" to sessionId,
                "restaurantId" to profile.account.restaurantId,
            ),
        )
        audit.record(
            profile.account.restaurantId.toString(), "USER", profile.account.id.toString(),
            "ADJUSTMENT_VOIDED", "ADJUSTMENT", adjustmentId.toString(), reason = reason,
        )
        return adjustmentView(adjustmentId, profile.account.restaurantId)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override fun voidCourtesyForCancelledItem(
        restaurantId: UUID,
        itemId: UUID,
        actorId: UUID,
        reason: String,
        now: java.time.Instant,
    ) {
        val row = jdbc.query(
            """
            SELECT a.id, a.session_id
            FROM bill_adjustment a
            JOIN customer_order o ON o.session_id = a.session_id
            WHERE a.order_item_id = :itemId AND a.restaurant_id = :restaurantId
              AND a.kind = 'COURTESY_ITEM' AND a.status = 'APPLIED'
              AND o.restaurant_id = a.restaurant_id
            """.trimIndent(),
            mapOf("itemId" to itemId, "restaurantId" to restaurantId),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getObject("session_id", UUID::class.java) }
            .firstOrNull() ?: return
        lockOpenSession(row.second, restaurantId)
        val changed = jdbc.update(
            """
            UPDATE bill_adjustment
            SET status = 'VOIDED', voided_by = :voidedBy, voided_at = :voidedAt, void_reason = :reason
            WHERE id = :id AND restaurant_id = :restaurantId AND status = 'APPLIED'
            """.trimIndent(),
            mapOf(
                "voidedAt" to java.sql.Timestamp.from(now),
                "voidedBy" to actorId,
                "reason" to reason,
                "id" to row.first,
                "restaurantId" to restaurantId,
            ),
        )
        if (changed == 1) {
            audit.record(
                restaurantId.toString(), "USER", actorId.toString(),
                "ADJUSTMENT_VOIDED", "ADJUSTMENT", row.first.toString(), reason = reason,
            )
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun listGuestPayments(authorization: String?): List<Map<String, Any>> {
        val bill = billQuery.getCurrentBill(authorization)
        @Suppress("UNCHECKED_CAST")
        return bill["payments"] as List<Map<String, Any>>
    }

    @Transactional
    fun executeIdempotent(
        scope: String,
        key: String,
        body: String,
        action: () -> BillingResult,
    ): org.springframework.http.ResponseEntity<Map<String, Any>> =
        idempotencyService.executeIdempotent(scope, key, body) {
            val result = action()
            val response = org.springframework.http.ResponseEntity.status(result.status)
            if (result.status >= 400) {
                response.contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
            }
            response.body(result.body)
        }.let { response ->
            if (!response.statusCode.isError) {
                response
            } else {
                org.springframework.http.ResponseEntity.status(response.statusCode)
                    .headers { it.putAll(response.headers) }
                    .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                    .body(response.body)
            }
        }

    fun guestPaymentIdempotencyScope(authorization: String?): String {
        val access = guestAccess(authorization)
        if (access.revoked) throw DomainException(ErrorCode.SESSION_ACCESS_REVOKED, "Guest access was revoked")
        return idempotencyScope("participant", access.sessionId, access.participantId)
    }

    fun cashPaymentIdempotencyScope(sessionId: UUID, profile: StaffProfile): String =
        idempotencyScope("user", sessionId, profile.account.id)

    fun paymentVoidIdempotencyScope(paymentId: UUID, profile: StaffProfile): String =
        idempotencyScope(
            "user",
            findPaymentSession(paymentId, profile.account.restaurantId),
            profile.account.id,
        )

        fun createRefundIdempotencyScope(sessionId: UUID, profile: StaffProfile): String =
            idempotencyScope("user", sessionId, profile.account.id)

        fun adjustmentCreateIdempotencyScope(sessionId: UUID, profile: StaffProfile): String =
        idempotencyScope("user", sessionId, profile.account.id)

    fun adjustmentVoidIdempotencyScope(adjustmentId: UUID, profile: StaffProfile): String =
        idempotencyScope(
            "user",
            findAdjustmentSession(adjustmentId, profile.account.restaurantId),
            profile.account.id,
        )

    private fun paymentResult(paymentId: UUID, sessionId: UUID, restaurantId: UUID, status: Int): BillingResult {
        val bill = billQuery.billForSession(sessionId, restaurantId)
        @Suppress("UNCHECKED_CAST")
        val payments = bill["payments"] as List<Map<String, Any>>
        val payment = payments.first { it["id"] == paymentId.toString() }
        return BillingResult(status, mapOf("payment" to payment, "bill" to bill))
    }

    private fun insertPayment(payment: Payment, restaurantId: UUID) {
        jdbc.update(
            """
            INSERT INTO payment
                (id, restaurant_id, session_id, kind, method, status, amount_minor, tip_minor,
                 payer_participant_id, payer_label, reason, idempotency_key, created_at, voided_at)
            VALUES
                (:id, :restaurantId, :sessionId, :kind, :method, :status, :amountMinor, :tipMinor,
                 :participantId, :payerLabel, :reason, :key, :createdAt, :voidedAt)
            """.trimIndent(),
            mapOf(
                "id" to payment.id,
                "restaurantId" to restaurantId,
                "sessionId" to payment.sessionId,
                "kind" to payment.kind.name,
                "method" to payment.method.name,
                "status" to payment.status.name,
                "amountMinor" to payment.amount.minor,
                "tipMinor" to payment.tip.minor,
                "participantId" to payment.payerParticipantId,
                "payerLabel" to payment.payerLabel,
                "reason" to payment.reason,
                "key" to payment.idempotencyKey,
                "createdAt" to java.sql.Timestamp.from(clock.instant()),
                "voidedAt" to payment.voidedAt?.let(java.sql.Timestamp::from),
            ),
        )
    }

    private fun lockSession(sessionId: UUID, restaurantId: UUID): String =
        jdbc.query(
            """
            SELECT status FROM table_session
            WHERE id = :sessionId AND restaurant_id = :restaurantId
            FOR UPDATE
            """.trimIndent(),
            mapOf("sessionId" to sessionId, "restaurantId" to restaurantId),
        ) { rs, _ -> rs.getString("status") }.firstOrNull()
            ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Session not found")

    private fun lockOpenSession(sessionId: UUID, restaurantId: UUID) {
        if (lockSession(sessionId, restaurantId) != "OPEN") {
            throw DomainException(ErrorCode.SESSION_CLOSED, "Session is closed")
        }
    }

    private fun paymentContext(restaurantId: UUID, bill: Map<String, Any>): PaymentContext {
        val settings = settings(restaurantId)
        return PaymentContext(
            balance = com.somphie.billing.domain.Balance((bill.getValue("balanceMinor") as Number).toLong()),
            grossCharges = Money.of((bill.getValue("chargesMinor") as Number).toLong()),
            minPayment = Money.of(settings.minPayment),
            maxTipPercent = settings.maxTipPercent,
        )
    }

    private fun settings(restaurantId: UUID): RestaurantBillingSettings =
        jdbc.query(
            """
            SELECT min_payment_minor, max_tip_percent, max_staff_discount_percent, max_staff_adjustment_minor
            FROM restaurant_settings WHERE restaurant_id = :restaurantId
            """.trimIndent(),
            mapOf("restaurantId" to restaurantId),
        ) { rs, _ ->
            RestaurantBillingSettings(
                rs.getLong("min_payment_minor"),
                rs.getInt("max_tip_percent"),
                rs.getInt("max_staff_discount_percent"),
                rs.getObject("max_staff_adjustment_minor")?.let { rs.getLong("max_staff_adjustment_minor") },
            )
        }.firstOrNull() ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Restaurant settings not found")

    private fun adjustmentViews(sessionId: UUID, restaurantId: UUID): List<Map<String, Any>> =
        jdbc.query(
            """
            SELECT a.id, a.kind, a.status, a.amount_minor, a.percent_bp, a.order_item_id, a.reason_code,
                   a.reason_text, a.created_at, a.voided_at, a.void_reason, u.display_name AS created_by
            FROM bill_adjustment a
            JOIN app_user u ON u.id = a.created_by AND u.restaurant_id = a.restaurant_id
            WHERE a.session_id = :sessionId AND a.restaurant_id = :restaurantId
            ORDER BY a.created_at, a.id
            """.trimIndent(),
            mapOf("sessionId" to sessionId, "restaurantId" to restaurantId),
        ) { rs, _ -> adjustmentMap(rs) }

    private fun adjustmentView(id: UUID, restaurantId: UUID): Map<String, Any> =
        jdbc.query(
            """
            SELECT a.id, a.kind, a.status, a.amount_minor, a.percent_bp, a.order_item_id, a.reason_code,
                   a.reason_text, a.created_at, a.voided_at, a.void_reason, u.display_name AS created_by
            FROM bill_adjustment a
            JOIN app_user u ON u.id = a.created_by AND u.restaurant_id = a.restaurant_id
            WHERE a.id = :id AND a.restaurant_id = :restaurantId
            """.trimIndent(),
            mapOf("id" to id, "restaurantId" to restaurantId),
        ) { rs, _ -> adjustmentMap(rs) }.firstOrNull()
            ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Adjustment not found")

    private fun adjustmentMap(rs: java.sql.ResultSet): Map<String, Any> = linkedMapOf<String, Any>(
        "id" to rs.getObject("id", UUID::class.java).toString(),
        "kind" to rs.getString("kind"),
        "status" to rs.getString("status"),
        "amountMinor" to rs.getLong("amount_minor"),
        "reasonCode" to rs.getString("reason_code"),
        "appliedBy" to rs.getString("created_by"),
        "appliedAt" to rs.getTimestamp("created_at").toInstant().toString(),
    ).apply {
        rs.getObject("percent_bp")?.let { put("percentBp", rs.getInt("percent_bp")) }
        rs.getObject("order_item_id", UUID::class.java)?.let { put("orderItemId", it.toString()) }
        rs.getString("reason_text")?.let { put("reasonText", it) }
        rs.getString("void_reason")?.let { put("voidReason", it) }
        rs.getTimestamp("voided_at")?.toInstant()?.let { put("voidedAt", it.toString()) }
    }

    private fun findPaymentSession(paymentId: UUID, restaurantId: UUID): UUID =
        jdbc.query(
            "SELECT session_id FROM payment WHERE id = :id AND restaurant_id = :restaurantId",
            mapOf("id" to paymentId, "restaurantId" to restaurantId),
        ) { rs, _ -> rs.getObject("session_id", UUID::class.java) }.firstOrNull()
            ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Payment not found")

    private fun findAdjustmentSession(id: UUID, restaurantId: UUID): UUID =
        jdbc.query(
            "SELECT session_id FROM bill_adjustment WHERE id = :id AND restaurant_id = :restaurantId",
            mapOf("id" to id, "restaurantId" to restaurantId),
        ) { rs, _ -> rs.getObject("session_id", UUID::class.java) }.firstOrNull()
            ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Adjustment not found")

    private fun requirePermission(profile: StaffProfile, vararg permissions: String) {
        if (permissions.none { it in profile.permissions }) {
            throw DomainException(ErrorCode.FORBIDDEN, "Staff permission is required")
        }
    }

    private fun validatePaymentRequest(amount: Long, tip: Long) {
        validateAmount(amount, allowZero = true)
        validateAmount(tip, allowZero = true)
    }

    private fun validateAmount(amount: Long, allowZero: Boolean = false) {
        if (amount < 0 || (!allowZero && amount == 0L)) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Amount must be positive")
        }
    }

    private fun nonNegativeMoney(value: Long): Money {
        validateAmount(value, allowZero = true)
        return Money.of(value)
    }

    private fun requireReason(reason: String) {
        if (reason.isBlank() || reason.length > 140) {
            throw DomainException(ErrorCode.VALIDATION_FAILED, "Reason is required and must be at most 140 characters")
        }
    }

    private fun requireReasonCode(value: String) {
        if (value !in ADJUSTMENT_REASON_CODES) throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid reasonCode")
    }

    private fun parseUuid(value: String): UUID = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw DomainException(ErrorCode.VALIDATION_FAILED, "Invalid UUID")
    }

    private fun guestToken(authorization: String?): String =
        authorization?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substringAfter(' ')
            ?.takeIf(String::isNotBlank)
            ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "A guest token is required")

    private fun guestAccess(authorization: String?): GuestAccess {
        val tokenHash = hashToken(guestToken(authorization))
        return jdbc.query(
            """
            SELECT sp.id AS participant_id, sp.session_id, sp.revoked_at, ts.restaurant_id
            FROM session_participant sp
            JOIN table_session ts ON ts.id = sp.session_id
            WHERE sp.token_hash = :tokenHash
            """.trimIndent(),
            mapOf("tokenHash" to tokenHash),
        ) { rs, _ ->
            GuestAccess(
                rs.getObject("participant_id", UUID::class.java),
                rs.getObject("session_id", UUID::class.java),
                rs.getObject("restaurant_id", UUID::class.java),
                rs.getTimestamp("revoked_at") != null,
            )
        }.firstOrNull() ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "Invalid guest token")
    }

    private fun idempotencyScope(actorType: String, sessionId: UUID, actorId: UUID): String =
        "billing:$actorType:$sessionId:$actorId"

    private fun requireMovementKeyAvailable(sessionId: UUID, idempotencyKey: String) {
        val alreadyUsed = jdbc.query(
            """
            SELECT EXISTS (
                SELECT 1 FROM payment WHERE session_id = :sessionId AND idempotency_key = :key
                UNION ALL
                SELECT 1 FROM bill_adjustment WHERE session_id = :sessionId AND idempotency_key = :key
            )
            """.trimIndent(),
            mapOf("sessionId" to sessionId, "key" to idempotencyKey),
        ) { rs, _ -> rs.getBoolean(1) }.first()
        if (alreadyUsed) {
            throw DomainException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency key was already used in this session")
        }
    }

    private fun hashToken(token: String): String =
        java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
        )

    private fun declinedResult(paymentId: UUID): BillingResult {
        val problem = linkedMapOf<String, Any>(
            "type" to "https://api.somphiezurich.example/errors/payment-declined",
            "title" to "No se pudo completar la operación",
            "status" to 422,
            "code" to "PAYMENT_DECLINED",
            "paymentId" to paymentId.toString(),
        )
        MDC.get("traceId")?.let { problem["traceId"] = it }
        return BillingResult(422, problem)
    }

    private data class GuestAccess(
        val participantId: UUID,
        val sessionId: UUID,
        val restaurantId: UUID,
        val revoked: Boolean,
    )

    private data class RestaurantBillingSettings(
        val minPayment: Long,
        val maxTipPercent: Int,
        val maxStaffDiscountPercent: Int,
        val maxStaffAbsolute: Long?,
    )

    private data class AdjustmentToVoid(
        val status: String,
        val appliedAt: java.time.Instant,
        val appliedBy: UUID,
    )

    private companion object {
        val ADJUSTMENT_REASON_CODES =
            setOf("COURTESY_HOUSE", "SERVICE_ISSUE", "PROMOTION", "STAFF", "ERROR_CORRECTION", "OTHER")
    }
}
