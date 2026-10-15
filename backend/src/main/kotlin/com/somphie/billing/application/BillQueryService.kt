package com.somphie.billing.application

import com.somphie.billing.domain.AdjustmentLine
import com.somphie.billing.domain.AdjustmentStatus
import com.somphie.billing.domain.BillCalculator
import com.somphie.billing.domain.BillSummary
import com.somphie.billing.domain.ChargeLine
import com.somphie.billing.domain.PaymentKind
import com.somphie.billing.domain.PaymentLine
import com.somphie.billing.domain.PaymentStatus
import com.somphie.floor.application.SessionBillPort
import com.somphie.floor.application.SessionBillSummary
import com.somphie.orders.application.BillableOrderLine
import com.somphie.orders.application.OrderChargesPort
import com.somphie.orders.application.ReceiptOrderLine
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.domain.Money
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.HexFormat
import java.util.UUID

data class SessionReceipt(
    val sessionId: UUID,
    val restaurantName: String,
    val tableLabel: String,
    val timezone: String,
    val openedAt: Instant,
    val closedAt: Instant,
    val closeReason: String,
    val receiptLegend: String,
    val currency: String,
    val lines: List<ReceiptOrderLine>,
    val adjustments: List<Map<String, Any>>,
    val payments: List<Map<String, Any>>,
    val chargesMinor: Long,
    val adjustmentsMinor: Long,
    val paidMinor: Long,
    val balanceMinor: Long,
    val tipsMinor: Long,
)

@Service
class BillQueryService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val orderCharges: OrderChargesPort,
    private val clock: Clock,
) : BillableChargesPort, SessionBillPort {
    // La fila de sesión bloqueada serializa esta lectura con todos los escritores de la cuenta.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun getCurrentBill(authorization: String?): Map<String, Any> {
        val access = guestAccess(authorization)
        if (access.revoked) {
            throw DomainException(ErrorCode.SESSION_ACCESS_REVOKED, "Guest access was revoked")
        }
        return billForSession(access.sessionId, access.restaurantId)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun getCurrentReceipt(authorization: String?): SessionReceipt {
        val access = guestAccess(authorization)
        if (access.revoked) {
            throw DomainException(ErrorCode.SESSION_ACCESS_REVOKED, "Guest access was revoked")
        }
        val bill = billForSession(access.sessionId, access.restaurantId)
        val session = jdbc.query(
            """
            SELECT ts.status, ts.opened_at, ts.closed_at, ts.close_reason, dt.label,
                   r.name AS restaurant_name, r.timezone, r.currency, rs.receipt_legend
            FROM table_session ts
            JOIN dining_table dt ON dt.id = ts.table_id AND dt.restaurant_id = ts.restaurant_id
            JOIN restaurant r ON r.id = ts.restaurant_id
            JOIN restaurant_settings rs ON rs.restaurant_id = ts.restaurant_id
            WHERE ts.id = :sessionId AND ts.restaurant_id = :restaurantId
            FOR UPDATE OF ts
            """.trimIndent(),
            mapOf("sessionId" to access.sessionId, "restaurantId" to access.restaurantId),
        ) { rs, _ ->
            ReceiptSessionRow(
                status = rs.getString("status"),
                openedAt = rs.getTimestamp("opened_at").toInstant(),
                closedAt = rs.getTimestamp("closed_at")?.toInstant(),
                closeReason = rs.getString("close_reason"),
                tableLabel = rs.getString("label"),
                restaurantName = rs.getString("restaurant_name"),
                timezone = rs.getString("timezone"),
                currency = rs.getString("currency").trim(),
                receiptLegend = rs.getString("receipt_legend"),
            )
        }.firstOrNull() ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Session not found")
        if (session.status != "CLOSED") {
            throw DomainException(ErrorCode.RECEIPT_NOT_AVAILABLE, "Receipt is available after session closure")
        }
        val closedAt = session.closedAt
            ?: throw DomainException(ErrorCode.SESSION_CLOSED, "Closed session timestamp is missing")
        val adjustments = receiptRows(bill, "adjustments")
        val payments = receiptRows(bill, "payments")
        return SessionReceipt(
            sessionId = access.sessionId,
            restaurantName = session.restaurantName,
            tableLabel = session.tableLabel,
            timezone = session.timezone,
            openedAt = session.openedAt,
            closedAt = closedAt,
            closeReason = session.closeReason ?: "",
            receiptLegend = session.receiptLegend?.takeIf(String::isNotBlank) ?: DEFAULT_RECEIPT_LEGEND,
            currency = session.currency,
            lines = orderCharges.receiptLines(access.sessionId, access.restaurantId),
            adjustments = adjustments,
            payments = payments,
            chargesMinor = (bill.getValue("chargesMinor") as Number).toLong(),
            adjustmentsMinor = (bill.getValue("adjustmentsMinor") as Number).toLong(),
            paidMinor = (bill.getValue("paidMinor") as Number).toLong(),
            balanceMinor = (bill.getValue("balanceMinor") as Number).toLong(),
            tipsMinor = (bill.getValue("tipsMinor") as Number).toLong(),
        )
    }

    private fun guestAccess(authorization: String?): SessionBillAccess {
        val token = authorization
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.takeIf(String::isNotBlank)
            ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "A guest token is required")
        val tokenHash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
        )
        return jdbc.query(
            """
            SELECT ts.id AS session_id, ts.restaurant_id, sp.revoked_at
            FROM session_participant sp
            JOIN table_session ts ON ts.id = sp.session_id
            WHERE sp.token_hash = :tokenHash
            """.trimIndent(),
            mapOf("tokenHash" to tokenHash),
        ) { rs, _ ->
            SessionBillAccess(
                sessionId = rs.getObject("session_id", UUID::class.java),
                restaurantId = rs.getObject("restaurant_id", UUID::class.java),
                revoked = rs.getTimestamp("revoked_at") != null,
            )
        }.firstOrNull() ?: throw DomainException(ErrorCode.UNAUTHENTICATED, "Invalid guest token")
    }

    @Suppress("UNCHECKED_CAST")
    private fun receiptRows(bill: Map<String, Any>, key: String): List<Map<String, Any>> =
        bill.getValue(key) as List<Map<String, Any>>

    @Transactional(propagation = Propagation.MANDATORY)
    override fun billForSession(sessionId: UUID, restaurantId: UUID): Map<String, Any> {
        val session = jdbc.query(
            """
            SELECT ts.status, ts.closed_at, r.currency
            FROM table_session ts
            JOIN restaurant r ON r.id = ts.restaurant_id
            WHERE ts.id = :sessionId AND ts.restaurant_id = :restaurantId
            FOR UPDATE OF ts
            """.trimIndent(),
            mapOf("sessionId" to sessionId, "restaurantId" to restaurantId),
        ) { rs, _ ->
            Triple(rs.getString("status"), rs.getTimestamp("closed_at")?.toInstant(), rs.getString("currency").trim())
        }.firstOrNull() ?: throw DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Session not found")
        if (session.first == "CLOSED" &&
            (session.second == null || session.second!!.isBefore(Instant.now(clock).minusSeconds(CLOSED_SESSION_READ_SECONDS)))
        ) {
            throw DomainException(ErrorCode.SESSION_CLOSED, "Session is no longer available")
        }

        val lines = orderCharges.billableLines(sessionId, restaurantId)
        val adjustmentRows = jdbc.query(
            """
            SELECT id, kind, status, amount_minor
            FROM bill_adjustment
            WHERE session_id = :sessionId AND restaurant_id = :restaurantId
            ORDER BY created_at, id
            """.trimIndent(),
            mapOf("sessionId" to sessionId, "restaurantId" to restaurantId),
        ) { rs, _ ->
            val id = rs.getObject("id", UUID::class.java)
            val kind = rs.getString("kind")
            val status = AdjustmentStatus.valueOf(rs.getString("status"))
            val amount = rs.getLong("amount_minor")
            AdjustmentLine(status, Money.of(amount)) to linkedMapOf<String, Any>(
                "id" to id.toString(),
                "kind" to kind,
                "label" to if (kind == "COURTESY_ITEM") "Cortesía de la casa" else "Descuento",
                "amountMinor" to amount,
            )
        }
        val paymentRows = jdbc.query(
            """
            SELECT p.id, p.kind, p.method, p.status, p.amount_minor, p.tip_minor, p.payer_label,
                   p.created_at, sp.display_name AS payer_name
            FROM payment p
            LEFT JOIN session_participant sp ON sp.id = p.payer_participant_id
            WHERE p.session_id = :sessionId AND p.restaurant_id = :restaurantId
            ORDER BY p.created_at, p.id
            """.trimIndent(),
            mapOf("sessionId" to sessionId, "restaurantId" to restaurantId),
        ) { rs, _ ->
            val kind = PaymentKind.valueOf(rs.getString("kind"))
            val status = PaymentStatus.valueOf(rs.getString("status"))
            val amount = rs.getLong("amount_minor")
            val tip = rs.getLong("tip_minor")
            val item = linkedMapOf<String, Any>(
                "id" to rs.getObject("id", UUID::class.java).toString(),
                "kind" to kind.name,
                "method" to rs.getString("method"),
                "status" to status.name,
                "amountMinor" to amount,
                "tipMinor" to tip,
                "createdAt" to rs.getTimestamp("created_at").toInstant().toString(),
            )
            rs.getString("payer_name")?.let { item["payerName"] = it }
            rs.getString("payer_label")?.let { item["payerLabel"] = it }
            PaymentLine(kind, status, Money.of(amount), Money.of(tip)) to item
        }
        val summary = BillCalculator.summarize(
            lines.map { ChargeLine(Money.of(it.lineTotalMinor)) },
            adjustmentRows.map { it.first },
            paymentRows.map { it.first },
        )
        val pending = orderCharges.pendingOrders(sessionId, restaurantId)
        return linkedMapOf(
            "currency" to session.third,
            "chargesMinor" to summary.charges.minor,
            "adjustmentsMinor" to summary.adjustments.minor,
            "paidMinor" to summary.paid.minor,
            "balanceMinor" to summary.balance.minor,
            "tipsMinor" to summary.tips.minor,
            "lines" to lines.map {
                mapOf(
                    "orderCode" to it.orderCode,
                    "productName" to it.productName,
                    "quantity" to it.quantity,
                    "unitPriceMinor" to it.unitPriceMinor,
                    "lineTotalMinor" to it.lineTotalMinor,
                )
            },
            "adjustments" to adjustmentRows.filter { it.first.status == AdjustmentStatus.APPLIED }.map { it.second },
            "payments" to paymentRows.map { it.second },
            "pendingOrdersNotIncluded" to pending.map {
                mapOf("orderCode" to it.orderCode, "totalMinor" to it.totalMinor)
            },
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun billSummariesForSessions(
        sessionIds: List<UUID>,
        restaurantId: UUID,
    ): Map<UUID, SessionBillSummary> {
        if (sessionIds.isEmpty()) return emptyMap()
        val openSessionIds = jdbc.query(
            """
            SELECT id
            FROM table_session
            WHERE id IN (:sessionIds) AND restaurant_id = :restaurantId AND status = 'OPEN'
            ORDER BY id
            FOR UPDATE
            """.trimIndent(),
            mapOf("sessionIds" to sessionIds, "restaurantId" to restaurantId),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }
        if (openSessionIds.isEmpty()) return emptyMap()

        val chargeLines = orderCharges.billableLinesBySession(openSessionIds, restaurantId)
        val adjustments = jdbc.query(
            """
            SELECT session_id, status, amount_minor
            FROM bill_adjustment
            WHERE session_id IN (:sessionIds) AND restaurant_id = :restaurantId
            ORDER BY session_id, created_at, id
            """.trimIndent(),
            mapOf("sessionIds" to openSessionIds, "restaurantId" to restaurantId),
        ) { rs, _ ->
            rs.getObject("session_id", UUID::class.java) to AdjustmentLine(
                status = AdjustmentStatus.valueOf(rs.getString("status")),
                amount = Money.of(rs.getLong("amount_minor")),
            )
        }.groupBy({ it.first }, { it.second })
        val payments = jdbc.query(
            """
            SELECT session_id, kind, status, amount_minor, tip_minor
            FROM payment
            WHERE session_id IN (:sessionIds) AND restaurant_id = :restaurantId
            ORDER BY session_id, created_at, id
            """.trimIndent(),
            mapOf("sessionIds" to openSessionIds, "restaurantId" to restaurantId),
        ) { rs, _ ->
            rs.getObject("session_id", UUID::class.java) to PaymentLine(
                kind = PaymentKind.valueOf(rs.getString("kind")),
                status = PaymentStatus.valueOf(rs.getString("status")),
                amount = Money.of(rs.getLong("amount_minor")),
                tip = Money.of(rs.getLong("tip_minor")),
            )
        }.groupBy({ it.first }, { it.second })

        return openSessionIds.associateWith { sessionId ->
            val summary = BillCalculator.summarize(
                chargeLines[sessionId].orEmpty().map { ChargeLine(Money.of(it.lineTotalMinor)) },
                adjustments[sessionId].orEmpty(),
                payments[sessionId].orEmpty(),
            )
            SessionBillSummary(summary.charges.minor, summary.balance.minor)
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun chargesMinor(sessionId: UUID, restaurantId: UUID): Long =
        BillCalculator.summarize(
            orderCharges.billableLines(sessionId, restaurantId).map { ChargeLine(Money.of(it.lineTotalMinor)) },
            emptyList(),
            emptyList(),
        ).charges.minor

    private data class SessionBillAccess(
        val sessionId: UUID,
        val restaurantId: UUID,
        val revoked: Boolean,
    )

    private data class ReceiptSessionRow(
        val status: String,
        val openedAt: Instant,
        val closedAt: Instant?,
        val closeReason: String?,
        val tableLabel: String,
        val restaurantName: String,
        val timezone: String,
        val currency: String,
        val receiptLegend: String?,
    )

    private companion object {
        const val CLOSED_SESSION_READ_SECONDS = 24 * 60 * 60L
        const val DEFAULT_RECEIPT_LEGEND = "Comprobante no fiscal — no válido como factura"
    }
}
