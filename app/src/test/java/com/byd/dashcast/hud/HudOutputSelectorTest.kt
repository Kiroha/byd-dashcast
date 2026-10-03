package com.byd.dashcast.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HudOutputSelectorTest {

    @Test
    fun `off never selects an output`() {
        assertNull(HudOutputSelector.select(HudOutputMode.Off, HudOutputCapabilities.LEGACY_DL3))
    }

    @Test
    fun `auto selects only the explicitly proven route`() {
        assertEquals(
            HudOutputRoute.LegacyDl3,
            HudOutputSelector.select(HudOutputMode.Auto, HudOutputCapabilities.LEGACY_DL3)
        )

        val supportedButNotAuto = HudOutputCapabilities(
            supportedRoutes = setOf(HudOutputRoute.LegacyDl3)
        )
        assertNull(HudOutputSelector.select(HudOutputMode.Auto, supportedButNotAuto))
    }

    @Test
    fun `unknown platform has no automatic or manual legacy fallback`() {
        assertNull(HudOutputSelector.select(HudOutputMode.Auto, HudOutputCapabilities.NONE))
        assertNull(HudOutputSelector.select(HudOutputMode.LegacyDl3, HudOutputCapabilities.NONE))
    }

    @Test
    fun `someip selection requires the exact proven profile`() {
        val ui7 = HudOutputRoute.SomeIp(SomeIpHudProfile.ALTERNATIVE_UI7)
        val capabilities = HudOutputCapabilities(
            supportedRoutes = setOf(ui7),
            autoRoute = ui7
        )

        assertEquals(
            ui7,
            HudOutputSelector.select(
                HudOutputMode.SomeIp(SomeIpHudProfile.ALTERNATIVE_UI7),
                capabilities
            )
        )
        assertNull(
            HudOutputSelector.select(
                HudOutputMode.SomeIp(SomeIpHudProfile.ALTERNATIVE_CN_D5),
                capabilities
            )
        )
        assertNull(HudOutputSelector.select(HudOutputMode.LegacyDl3, capabilities))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `auto route cannot name an unproven capability`() {
        HudOutputCapabilities(
            supportedRoutes = emptySet(),
            autoRoute = HudOutputRoute.SomeIp(SomeIpHudProfile.LAUNCHER_MAP_CN)
        )
    }
}
