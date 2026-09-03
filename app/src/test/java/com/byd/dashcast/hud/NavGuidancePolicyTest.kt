package com.byd.dashcast.hud

import com.byd.dashcast.system.CanBusController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavGuidancePolicyTest {

    @Test
    fun `a recognized maneuver and distance form complete guidance`() {
        assertTrue(MapNotificationListenerService.hasGuidanceSignal(
            CanBusController.ICON_TURN_RIGHT, 300
        ))
        assertTrue(MapNotificationListenerService.isCompleteGuidance(
            CanBusController.ICON_TURN_RIGHT, 300
        ))
    }

    @Test
    fun `distance without a maneuver is diagnostic only`() {
        assertTrue(MapNotificationListenerService.hasGuidanceSignal(-1, 300))
        assertFalse(MapNotificationListenerService.isCompleteGuidance(-1, 300))
    }

    @Test
    fun `a maneuver without a parsed distance is diagnostic only`() {
        assertTrue(MapNotificationListenerService.hasGuidanceSignal(
            CanBusController.ICON_TURN_LEFT, -1
        ))
        assertFalse(MapNotificationListenerService.isCompleteGuidance(
            CanBusController.ICON_TURN_LEFT, -1
        ))
    }

    @Test
    fun `a notification with neither signal is ignored`() {
        assertFalse(MapNotificationListenerService.hasGuidanceSignal(-1, -1))
    }
}