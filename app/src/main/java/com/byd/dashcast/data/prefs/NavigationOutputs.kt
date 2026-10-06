package com.byd.dashcast.data.prefs

/** Effective navigation destinations, independent of app projection and HUD hardware presence. */
data class NavigationOutputs(val hud: Boolean, val cluster: Boolean) {
    val enabled: Boolean get() = hud || cluster

    // The OEM Amap receiver writes CAN itself, so it must never serve a single-destination mode.
    val useAmapBridge: Boolean get() = hud && cluster

    companion object {
        val NONE = NavigationOutputs(hud = false, cluster = false)
    }
}
