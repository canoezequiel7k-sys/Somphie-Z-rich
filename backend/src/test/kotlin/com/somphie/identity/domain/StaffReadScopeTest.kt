package com.somphie.identity.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StaffReadScopeTest {
    @Test
    fun `kitchen alone is station scoped`() {
        assertTrue(StaffReadScope.isKitchenOnly(setOf("KITCHEN")))
    }

    @Test
    fun `restaurant wide role keeps broad read scope when combined with kitchen`() {
        assertFalse(StaffReadScope.isKitchenOnly(setOf("KITCHEN", "BAR")))
        assertFalse(StaffReadScope.isKitchenOnly(setOf("KITCHEN", "WAITER")))
        assertFalse(StaffReadScope.isKitchenOnly(setOf("KITCHEN", "OWNER")))
    }

    @Test
    fun `non kitchen roles are not station scoped`() {
        assertFalse(StaffReadScope.isKitchenOnly(setOf("BAR")))
        assertFalse(StaffReadScope.isKitchenOnly(setOf("WAITER")))
    }
}
