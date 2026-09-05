package com.byd.dashcast.hud

import android.app.Notification
import android.service.notification.StatusBarNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@Suppress("DEPRECATION")
class MapNotificationSourceFailoverTest {

    @Test
    fun `removing current source selects newest remaining supported navigation`() {
        val maps = notification("com.google.android.apps.maps", 1, 100L)
        val olderWaze = notification("com.waze", 2, 50L)
        val removedWaze = notification("com.waze", 3, 200L)
        val unsupportedNewer = notification("com.example.nav", 4, 300L)

        val remaining = MapNotificationListenerService.remainingNavigationNotifications(
            arrayOf(olderWaze, unsupportedNewer, removedWaze, maps),
            removedWaze.key,
        )

        assertEquals(listOf(maps.key, olderWaze.key), remaining.map { it.key })
    }

    @Test
    fun `removal replays remaining guidance before closing the HUD`() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "app/src/main/java/com/byd/dashcast/hud").isDirectory }
        assertTrue("could not locate the repo root", root != null)
        val source = java.io.File(
            root,
            "app/src/main/java/com/byd/dashcast/hud/MapNotificationListenerService.kt"
        ).readText()
        val removal = source.substringAfter("override fun onNotificationRemoved")
            .substringBefore("// ─── HUD write dispatch")

        assertTrue(removal.indexOf("replayRemainingNavigation(key)") in
            0 until removal.indexOf("postNavClose()"))
    }

    private fun notification(pkg: String, id: Int, postTime: Long): StatusBarNotification {
        val notification = Notification().apply {
            flags = Notification.FLAG_ONGOING_EVENT
            category = Notification.CATEGORY_NAVIGATION
        }
        return StatusBarNotification(
            pkg,
            pkg,
            id,
            "tag-$id",
            10_000 + id,
            20_000 + id,
            0,
            notification,
            android.os.Process.myUserHandle(),
            postTime,
        )
    }
}