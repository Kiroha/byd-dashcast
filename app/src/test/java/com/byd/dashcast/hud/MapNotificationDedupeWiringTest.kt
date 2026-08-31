package com.byd.dashcast.hud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapNotificationDedupeWiringTest {

    @Test
    fun `big text participates in notification identity and reset`() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "app/src/main/java/com/byd/dashcast/hud").isDirectory }
        assertTrue("could not locate the repo root", root != null)
        val source = java.io.File(
            root,
            "app/src/main/java/com/byd/dashcast/hud/MapNotificationListenerService.java"
        ).readText()

        assertTrue(source.contains("bigText, lastBigText"))
        assertTrue(source.contains("sbn.getKey(), lastNotificationKey"))
        assertTrue(source.contains("lastBigText = bigText;"))
        assertTrue(source.contains("lastNotificationKey = sbn.getKey();"))
        assertTrue(source.contains("lastBigText = \"\";"))
        assertTrue(source.contains("lastNotificationKey = null;"))
    }

    @Test
    fun `identical text from a different notification source is not deduplicated`() {
        assertFalse(MapNotificationListenerService.isSameNotificationContent(
            "maps-key", "waze-key",
            "Turn right", "Turn right",
            "in 200 m", "in 200 m",
            "Turn right in 200 m", "Turn right in 200 m",
            "", "",
        ))
        assertTrue(MapNotificationListenerService.isSameNotificationContent(
            "maps-key", "maps-key",
            "Turn right", "Turn right",
            "in 200 m", "in 200 m",
            "Turn right in 200 m", "Turn right in 200 m",
            "", "",
        ))
    }

    @Test
    fun `listener disconnect clears dedupe state before closing`() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "app/src/main/java/com/byd/dashcast/hud").isDirectory }
        assertTrue("could not locate the repo root", root != null)
        val source = java.io.File(
            root,
            "app/src/main/java/com/byd/dashcast/hud/MapNotificationListenerService.java"
        ).readText()
        val disconnected = source.substringAfter("public void onListenerDisconnected()")
            .substringBefore("@Override", "")

        assertTrue(disconnected.indexOf("clearTrackedNavigation()") in
            0 until disconnected.indexOf("postNavClose()"))
    }

}