package com.byd.dashcast.hud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HudDeliveryTrackerTest {

    @Test
    fun `only an acknowledged current generation refreshes liveness`() {
        val tracker = HudDeliveryTracker()
        val first = tracker.beginContent()
        val second = tracker.beginContent()

        tracker.markDelivered(first)
        assertFalse(tracker.currentContentWasDelivered())

        tracker.markDelivered(second)
        assertTrue(tracker.currentContentWasDelivered())
    }

    @Test
    fun `late stale completion cannot erase a newer acknowledgement`() {
        val tracker = HudDeliveryTracker()
        val first = tracker.beginContent()
        val second = tracker.beginContent()

        tracker.markDelivered(second)
        tracker.markDelivered(first)

        assertTrue(tracker.currentContentWasDelivered())
    }

    @Test
    fun `invalidating content also invalidates its acknowledgement`() {
        val tracker = HudDeliveryTracker()
        val generation = tracker.beginContent()
        tracker.markDelivered(generation)
        tracker.invalidate()

        assertFalse(tracker.currentContentWasDelivered())
    }

    @Test
    fun `production advances watchdog only after delivery is known`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/com/byd/dashcast/hud/HudController.java").isFile }
        assertTrue("could not locate the repo root", root != null)
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/hud/HudController.java").readText()
        val update = source.substringAfter("public synchronized boolean updateNavigation")
            .substringBefore("public synchronized void closeNavigation")

        val deliveryDecision = update.indexOf("boolean delivered =")
        val watchdogWrite = update.indexOf("lastUpdateMs = SystemClock.elapsedRealtime()")
        assertTrue(deliveryDecision >= 0)
        assertTrue(watchdogWrite > deliveryDecision)
    }
}