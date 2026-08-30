package com.byd.dashcast.hud

import com.byd.dashcast.system.CanBusController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [ClusterNavPusher.toAmapIcon] / [ClusterNavPusher.roundaboutExitNum] against the cluster
 * turn-icon namespace decompiled from the firmware Qt QML (Navigation.qml / SimpleNavi.qml,
 * switch(turnIconId) 0..28, identical across cluster_theme1/2.rcc).
 */
class ClusterNavPusherTest {

    @Test
    fun `cardinal maneuvers map to the firmware ids`() {
        assertEquals(2, ClusterNavPusher.toAmapIcon(CanBusController.ICON_TURN_LEFT))
        assertEquals(3, ClusterNavPusher.toAmapIcon(CanBusController.ICON_TURN_RIGHT))
        assertEquals(9, ClusterNavPusher.toAmapIcon(CanBusController.ICON_STRAIGHT_SOLID))
        assertEquals(20, ClusterNavPusher.toAmapIcon(CanBusController.ICON_STRAIGHT_DOTTED)) // direct
        assertEquals(4, ClusterNavPusher.toAmapIcon(CanBusController.ICON_SLIGHT_LEFT))
        assertEquals(5, ClusterNavPusher.toAmapIcon(CanBusController.ICON_SLIGHT_RIGHT))
        assertEquals(6, ClusterNavPusher.toAmapIcon(CanBusController.ICON_SHARP_LEFT))
        assertEquals(7, ClusterNavPusher.toAmapIcon(CanBusController.ICON_SHARP_RIGHT))
    }

    @Test
    fun `both U-turns map to distinct real glyphs — the right U-turn regression guard`() {
        assertEquals(8, ClusterNavPusher.toAmapIcon(CanBusController.ICON_U_TURN_LEFT))
        // Was 0 (a no-op that left the previous glyph on screen); firmware id 19 = turn_right_about.
        assertEquals(19, ClusterNavPusher.toAmapIcon(CanBusController.ICON_U_TURN_RIGHT))
    }

    @Test
    fun `POI glyphs map correctly`() {
        assertEquals(13, ClusterNavPusher.toAmapIcon(CanBusController.ICON_PARKING_CAFE))
        assertEquals(14, ClusterNavPusher.toAmapIcon(CanBusController.ICON_TOLLBOOTH))
        assertEquals(15, ClusterNavPusher.toAmapIcon(CanBusController.ICON_DESTINATION))
        assertEquals(16, ClusterNavPusher.toAmapIcon(CanBusController.ICON_TUNNEL))
    }

    @Test
    fun `roundabout direction — CCW is left(17), CW is right(11)`() {
        assertEquals(17, ClusterNavPusher.toAmapIcon(CanBusController.ICON_ROUNDABOUT_CCW_1_LAP))
        assertEquals(17, ClusterNavPusher.toAmapIcon(CanBusController.ICON_ROUNDABOUT_CCW_10_LAPS))
        assertEquals(11, ClusterNavPusher.toAmapIcon(CanBusController.ICON_ROUNDABOUT_CW_1_LAP))
        assertEquals(11, ClusterNavPusher.toAmapIcon(CanBusController.ICON_ROUNDABOUT_CW_10_LAPS))
        assertEquals(17, ClusterNavPusher.toAmapIcon(CanBusController.ICON_ROUNDABOUT_1_4_LEFT))
        assertEquals(11, ClusterNavPusher.toAmapIcon(CanBusController.ICON_ROUNDABOUT_1_4_RIGHT))
    }

    @Test
    fun `roundabout exit number is extracted for the numbered lap ids only`() {
        assertEquals(1, ClusterNavPusher.roundaboutExitNum(CanBusController.ICON_ROUNDABOUT_CCW_1_LAP))
        assertEquals(10, ClusterNavPusher.roundaboutExitNum(CanBusController.ICON_ROUNDABOUT_CCW_10_LAPS))
        assertEquals(1, ClusterNavPusher.roundaboutExitNum(CanBusController.ICON_ROUNDABOUT_CW_1_LAP))
        assertEquals(10, ClusterNavPusher.roundaboutExitNum(CanBusController.ICON_ROUNDABOUT_CW_10_LAPS))
        // Non-numbered maneuvers carry no exit index.
        assertEquals(0, ClusterNavPusher.roundaboutExitNum(CanBusController.ICON_TURN_LEFT))
        assertEquals(0, ClusterNavPusher.roundaboutExitNum(CanBusController.ICON_ROUNDABOUT_1_4_LEFT))
    }

    @Test
    fun `unknown ids fall back to straight, never a turn`() {
        assertEquals(9, ClusterNavPusher.toAmapIcon(0))
        assertEquals(9, ClusterNavPusher.toAmapIcon(9999))
    }

    @Test
    fun `only the confirmed AutoContainer success code enables navigation mode`() {
        assertTrue("legacy daemon has no result field", ClusterNavPusher.activationAccepted(null))
        assertTrue(ClusterNavPusher.activationAccepted(0))
        assertFalse(ClusterNavPusher.activationAccepted(-1))
        assertFalse(ClusterNavPusher.activationAccepted(1))
    }

    @Test
    fun `production enable reads the native result before setting local state`() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "app/src/main/java/com/byd/dashcast/hud").isDirectory }
        assertTrue("could not locate the repo root", root != null)
        val source = java.io.File(
            root,
            "app/src/main/java/com/byd/dashcast/hud/ClusterNavPusher.kt"
        ).readText()
        val enable = source.substringAfter("fun enable()")
            .substringBefore("internal fun activationAccepted")

        val resultCall = enable.indexOf("autoContainerSendInfoResultCompatible")
        val acceptedGate = enable.indexOf("if (!activationAccepted(result))")
        val stateCommit = enable.indexOf("enabled = true")
        assertTrue(resultCall >= 0)
        assertTrue(acceptedGate > resultCall)
        assertTrue(stateCommit > acceptedGate)

        val proxy = java.io.File(
            root,
            "app/src/main/java/com/byd/dashcast/proxy/ProxyClient.java"
        ).readText()
        val compatible = proxy.substringAfter("autoContainerSendInfoResultCompatible")
            .substringBefore("/**", "")
        assertTrue(compatible.indexOf("callWithRetry") in
            0 until compatible.indexOf("supportsProtocol(20)"))
    }
}
