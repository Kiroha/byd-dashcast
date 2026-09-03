package com.byd.dashcast.hud

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HudAmapBroadcastTest {

    @Test
    fun `navigation broadcast is explicit to the verified OEM package`() {
        val intent = HudController.newAmapIntent()

        assertEquals("AUTONAVI_STANDARD_BROADCAST_SEND", intent.action)
        assertEquals("com.example.amapservice", intent.`package`)
    }
}