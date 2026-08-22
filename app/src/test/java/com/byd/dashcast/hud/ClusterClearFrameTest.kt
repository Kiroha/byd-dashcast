package com.byd.dashcast.hud

import byd.fbs.naviInfo.NaviInfo
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The end-of-route frame — the one whose whole job is to make something disappear.
 *
 * It used to clear with naviState = 0 and nextTurnIcon = 0, and both of those mean "carry on":
 * 0 and 1 are the two GUIDING naviStates (docs/HUD_DILINK3_HANDOFF.md:146), and icon id 0 is a
 * documented no-op that leaves the cluster showing whatever arrow it already had. So the last
 * manoeuvre arrow stayed lit after navigation ended, and nothing in the app could tell.
 *
 * These are the values the OEM itself writes in AmapService.reSetGuideInfo, and the ones this
 * app's own broadcast close path (HudController.sendAmapStopBroadcast) has always used. A test is
 * the only thing that can catch this regressing, because the failure is silent everywhere except
 * on an owner's instrument cluster.
 */
class ClusterClearFrameTest {

    private val clear: NaviInfo
        get() = NaviInfo.getRootAsNaviInfo(ByteBuffer.wrap(ClusterNavPusher.buildClearPayload()))

    @Test
    fun `the clear frame is not a guiding frame`() {
        assertEquals("the OEM's stopped naviState", 9, clear.naviState())
        assertNotEquals("0 is a guiding state, not a stopped one", 0, clear.naviState())
        assertNotEquals(1, clear.naviState())
    }

    @Test
    fun `the clear frame actually removes the arrow`() {
        // icon 0 keeps the previous glyph; -1 is what the OEM's reader treats as "no icon".
        assertEquals(-1, clear.nextTurnIcon())
    }

    /**
     * 0 is a legitimate value for each of these — it reads as "0 m to the turn, arriving now",
     * which is a guidance frame, not the absence of one.
     */
    @Test
    fun `the numeric fields are cleared, not zeroed`() {
        assertEquals(-1, clear.curToSegmentDist())
        assertEquals(-1, clear.routeRemainTime())
        assertEquals(-1, clear.routeRemainDist())
    }

    @Test
    fun `the road name is emptied`() {
        assertEquals("", clear.nextRouteName())
    }
}
