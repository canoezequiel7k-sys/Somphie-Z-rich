package com.somphie.identity.domain

object StaffReadScope {
    private val restaurantWideOrderReadRoles = setOf("OWNER", "WAITER", "BAR")

    fun isKitchenOnly(roles: Collection<String>): Boolean =
        "KITCHEN" in roles && roles.none { it in restaurantWideOrderReadRoles }
}
