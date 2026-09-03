package com.byd.dashcast.cluster.display

import com.byd.dashcast.cluster.display.ClusterGeometryPolicy.CMD_10_25
import com.byd.dashcast.cluster.display.ClusterGeometryPolicy.CMD_12_3
import com.byd.dashcast.cluster.display.ClusterGeometryPolicy.CMD_8_8
import com.byd.dashcast.cluster.display.ClusterGeometryPolicy.allowShapeCommand
import com.byd.dashcast.cluster.display.ClusterGeometryPolicy.isShapeCommand
import com.byd.dashcast.cluster.display.ClusterGeometryPolicy.isSmallPanelGeometry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterGeometryPolicyTest {

    /** The rule the maintainer stated from photographs sent by Atto 3 / Dolphin owners. */
    @Test
    fun `a small panel refuses both larger presets, however it was identified`() {
        // identified by the configured type
        assertFalse(allowShapeCommand(CMD_12_3, configuredType = CMD_8_8, latchedSmallPanel = false))
        assertFalse(allowShapeCommand(CMD_10_25, configuredType = CMD_8_8, latchedSmallPanel = false))
        // identified by a latched observation, with the type still at its 12.3" default —
        // the Atto 3 owner who never opened Settings, which is the case that actually happened
        assertFalse(allowShapeCommand(CMD_12_3, configuredType = CMD_12_3, latchedSmallPanel = true))
        assertFalse(allowShapeCommand(CMD_10_25, configuredType = CMD_12_3, latchedSmallPanel = true))
    }

    @Test
    fun `shrinking to the native small shape is always allowed`() {
        assertTrue(allowShapeCommand(CMD_8_8, configuredType = CMD_8_8, latchedSmallPanel = true))
        assertTrue(allowShapeCommand(CMD_8_8, configuredType = CMD_12_3, latchedSmallPanel = true))
    }

    /** The maintainer's own 10.25" car must keep working — it NEEDS cmd 30 for the ADAS fix. */
    @Test
    fun `a car with no small-panel evidence is never restricted`() {
        assertTrue(allowShapeCommand(CMD_12_3, configuredType = CMD_10_25, latchedSmallPanel = false))
        assertTrue(allowShapeCommand(CMD_10_25, configuredType = CMD_10_25, latchedSmallPanel = false))
        assertTrue(allowShapeCommand(CMD_12_3, configuredType = CMD_12_3, latchedSmallPanel = false))
    }

    @Test
    fun `non-shape commands are none of this policy's business`() {
        // 16 = enable projection, 18 = close it, 35 = DI4 mode, 0 = refresh Qt.
        for (cmd in intArrayOf(0, 16, 18, 35)) {
            assertFalse("$cmd must not be treated as a shape command", isShapeCommand(cmd))
            assertTrue("$cmd must pass through even on a small panel",
                allowShapeCommand(cmd, configuredType = CMD_8_8, latchedSmallPanel = true))
        }
    }

    @Test
    fun `only the three presets are shape commands`() {
        assertTrue(isShapeCommand(CMD_8_8))
        assertTrue(isShapeCommand(CMD_12_3))
        assertTrue(isShapeCommand(CMD_10_25))
        assertFalse(isShapeCommand(28))
        assertFalse(isShapeCommand(32))
    }

    @Test
    fun `the small panel is recognised in either orientation`() {
        assertTrue(isSmallPanelGeometry(1280, 480))
        assertTrue(isSmallPanelGeometry(480, 1280))
        assertFalse(isSmallPanelGeometry(1920, 720))
        assertFalse(isSmallPanelGeometry(0, 0))
    }
}
