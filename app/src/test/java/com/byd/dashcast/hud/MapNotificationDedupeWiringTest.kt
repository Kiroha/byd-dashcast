package com.byd.dashcast.hud

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

        assertTrue(source.contains("bigText.equals(lastBigText)"))
        assertTrue(source.contains("lastBigText = bigText;"))
        assertTrue(source.contains("lastBigText = \"\";"))
    }
}