package com.byd.dashcast.hud

import android.content.Context

/**
 * One complete navigation-output lifecycle.
 *
 * Acquisition (notifications/accessibility), windshield HUD, instrument cluster and app
 * projection are deliberately separate concerns. Implementations own only their output transport;
 * [HudController] remains the session orchestrator and the public entry point used by the listener.
 */
internal interface HudOutput {
    val route: HudOutputRoute

    /** Opens the output for the current navigation session. Must be idempotent. */
    fun begin(context: Context): HudOutputResult

    /** Applies one immutable navigation snapshot. */
    fun update(context: Context, data: HudNavigationData): HudOutputResult

    /** Closes and clears this output. Must be idempotent. */
    fun end(context: Context): HudOutputResult
}

/** Explicit transport result; callers must not equate an attempted write with delivery. */
internal enum class HudOutputResult(val delivered: Boolean) {
    DELIVERED(true),
    NOT_DELIVERED(false)
}

/** Profiles observed in OpenBYD 2.5. Their presence here does not declare compatibility. */
internal enum class SomeIpHudProfile {
    ALTERNATIVE_UI7,
    ALTERNATIVE_CN_D5,
    LAUNCHER_MAP_CN
}

/** User/configuration intent. SOME/IP always names the exact profile to avoid implicit guessing. */
internal sealed class HudOutputMode {
    object Off : HudOutputMode()
    object Auto : HudOutputMode()
    object LegacyDl3 : HudOutputMode()
    data class SomeIp(val profile: SomeIpHudProfile) : HudOutputMode()
}

/** A resolved, concrete output route. */
internal sealed class HudOutputRoute {
    object LegacyDl3 : HudOutputRoute()
    data class SomeIp(val profile: SomeIpHudProfile) : HudOutputRoute()
}

/**
 * Capabilities proven for the current vehicle/build.
 *
 * [autoRoute] is explicit rather than inferred from package presence or from the first supported
 * route. This keeps AUTO fail-closed when a platform has not been positively identified.
 */
internal data class HudOutputCapabilities(
    val supportedRoutes: Set<HudOutputRoute>,
    val autoRoute: HudOutputRoute? = null
) {
    init {
        require(autoRoute == null || autoRoute in supportedRoutes) {
            "AUTO route must be one of the proven supported routes"
        }
    }

    companion object {
        val NONE = HudOutputCapabilities(emptySet())
        val LEGACY_DL3 = HudOutputCapabilities(
            supportedRoutes = setOf(HudOutputRoute.LegacyDl3),
            autoRoute = HudOutputRoute.LegacyDl3
        )
    }
}

/** Pure selection policy, kept independent from Android/platform probing for unit tests. */
internal object HudOutputSelector {
    fun select(mode: HudOutputMode, capabilities: HudOutputCapabilities): HudOutputRoute? {
        val requested = when (mode) {
            HudOutputMode.Off -> return null
            HudOutputMode.Auto -> capabilities.autoRoute ?: return null
            HudOutputMode.LegacyDl3 -> HudOutputRoute.LegacyDl3
            is HudOutputMode.SomeIp -> HudOutputRoute.SomeIp(mode.profile)
        }
        return requested.takeIf { it in capabilities.supportedRoutes }
    }
}
