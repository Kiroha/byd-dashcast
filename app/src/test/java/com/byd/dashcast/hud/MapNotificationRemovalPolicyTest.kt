package com.byd.dashcast.hud

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MapNotificationRemovalPolicyTest {

    @Test
    fun `navigation category is eligible without ongoing flag`() {
        val notification = Notification().apply {
            flags = 0
            category = Notification.CATEGORY_NAVIGATION
        }

        assertTrue(MapNotificationListenerService.isNavigationNotification(notification))
    }

    @Test
    fun `unrelated non ongoing notification is ineligible`() {
        val notification = Notification().apply {
            flags = 0
            category = Notification.CATEGORY_MESSAGE
        }

        assertFalse(MapNotificationListenerService.isNavigationNotification(notification))
    }

    @Test
    fun `posted and removed callbacks share the eligibility predicate`() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "app/src/main/java/com/byd/dashcast/hud").isDirectory }
        assertTrue("could not locate the repo root", root != null)
        val source = java.io.File(
            root,
            "app/src/main/java/com/byd/dashcast/hud/MapNotificationListenerService.kt"
        ).readText()

        assertTrue(source.split("if (!isNavigationNotification(n)) return").size - 1 == 2)
    }
}