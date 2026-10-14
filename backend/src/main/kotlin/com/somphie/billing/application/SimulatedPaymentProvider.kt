package com.somphie.billing.application

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Aprobación local y determinista: nunca procesa ni conserva datos de tarjeta. */
@Component
class SimulatedPaymentProvider(
    @Value("\${DEMO_MODE:false}") private val demoMode: Boolean,
) {
    fun approve(amountMinor: Long): Boolean =
        !demoMode || amountMinor % 100L != 13L
}
