package com.somphie.billing.application

import java.util.UUID

/** Expone cargos derivados; no representa un saldo mientras falten pagos y ajustes persistidos. */
interface BillableChargesPort {
    fun chargesMinor(sessionId: UUID, restaurantId: UUID): Long
}
