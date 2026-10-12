package com.somphie.billing.domain

import com.somphie.orders.domain.ItemStatus
import com.somphie.orders.domain.itemIn
import com.somphie.shared.domain.DomainException
import com.somphie.shared.domain.ErrorCode
import com.somphie.shared.domain.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

/** Pruebas de la cortesía de un ítem (RN-ADJ-04, hallazgos H-25 y H-29). */
class CourtesyRulesTest {

    /** Adapta el estado del pedido a los hechos que la aplicación entrega al dominio de billing. */
    private fun facts(status: ItemStatus): CourtesyRules.ItemFacts {
        val item = itemIn(status)
        return CourtesyRules.ItemFacts(
            id = item.id,
            lineTotal = item.lineTotal,
            accepted = status != ItemStatus.PENDING && status != ItemStatus.CANCELLED,
            cancelled = status == ItemStatus.CANCELLED,
        )
    }

    @Test
    fun `a courtesy on an active item without courtesy returns its line total`() {
        val item = facts(ItemStatus.QUEUED)
        assertEquals(Money.of(485_000), CourtesyRules.lineTotalFor(item, emptySet()))
    }

    @Test
    fun `a delivered item can still receive a courtesy`() {
        val item = facts(ItemStatus.DELIVERED)
        assertEquals(item.lineTotal, CourtesyRules.lineTotalFor(item, emptySet()))
    }

    @Test
    fun `a cancelled item is ADJUSTMENT_ITEM_INVALID`() {
        val item = facts(ItemStatus.CANCELLED)
        val error = assertThrows(DomainException::class.java) { CourtesyRules.lineTotalFor(item, emptySet()) }
        assertEquals(ErrorCode.ADJUSTMENT_ITEM_INVALID, error.code)
        assertEquals(item.id, error.extra["itemId"])
    }

    @Test
    fun `an item that already has a courtesy is ADJUSTMENT_ITEM_INVALID`() {
        val item = facts(ItemStatus.READY)
        val error = assertThrows(DomainException::class.java) { CourtesyRules.lineTotalFor(item, setOf(item.id)) }
        assertEquals(ErrorCode.ADJUSTMENT_ITEM_INVALID, error.code)
    }

    @Test
    fun `a pending item of an order not yet accepted is ADJUSTMENT_ITEM_INVALID (H-29)`() {
        val item = facts(ItemStatus.PENDING)
        val error = assertThrows(DomainException::class.java) { CourtesyRules.lineTotalFor(item, emptySet()) }
        assertEquals(ErrorCode.ADJUSTMENT_ITEM_INVALID, error.code)
        assertEquals(item.id, error.extra["itemId"])
    }

    @Test
    fun `every accepted state can receive a courtesy and only pending and cancelled cannot`() {
        val allowed = listOf(ItemStatus.QUEUED, ItemStatus.IN_PREPARATION, ItemStatus.READY, ItemStatus.DELIVERED)
        allowed.forEach { status ->
            val item = facts(status)
            assertEquals(item.lineTotal, CourtesyRules.lineTotalFor(item, emptySet()), "status $status")
        }
        listOf(ItemStatus.PENDING, ItemStatus.CANCELLED).forEach { status ->
            val error = assertThrows(DomainException::class.java) { CourtesyRules.lineTotalFor(facts(status), emptySet()) }
            assertEquals(ErrorCode.ADJUSTMENT_ITEM_INVALID, error.code, "status $status")
        }
    }

    @Test
    fun `a courtesy on another item does not block this one`() {
        val item = facts(ItemStatus.READY)
        assertEquals(item.lineTotal, CourtesyRules.lineTotalFor(item, setOf(UUID.randomUUID())))
    }
}
