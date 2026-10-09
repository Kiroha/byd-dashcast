package com.byd.dashcast.hud

import android.app.Application
import android.app.Notification
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
@Suppress("DEPRECATION")
class NavigationIconCaptureTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test fun `snapshot accepts exact Maps variants and excludes unrelated or non-navigation notifications`() {
        val official = notification("com.google.android.apps.maps", 1, 10)
        val revanced = notification("app.revanced.android.apps.maps", 2, 20)
        val morphe = notification("app.morphe.android.apps.maps", 3, 30)
        val fake = notification("app.morphe.android.apps.maps.fake", 4, 40)
        val waze = notification("com.waze", 5, 50)
        val noise = notification("app.morphe.android.apps.maps", 6, 60).apply {
            notification.flags = 0
            notification.category = Notification.CATEGORY_MESSAGE
        }
        val selected = NavigationIconCapture.selectNotifications(arrayOf(official, revanced, morphe, fake, waze, noise, null))
        assertEquals(listOf(morphe.key, revanced.key, official.key), selected.map { it.key })
    }

    @Test fun `a burst of Maps notifications keeps only the four newest snapshots`() {
        val selected = NavigationIconCapture.selectNotifications(
            (1..20).map { notification("app.morphe.android.apps.maps", it, it.toLong()) }.toTypedArray())
        assertEquals(listOf(20, 19, 18, 17), selected.map { it.id })
    }

    @Test fun `bitmap icons are bounded and preserve the notification originals and privacy boundary`() {
        val original = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        original.eraseColor(Color.GREEN)
        val n = Notification.Builder(context, "navigation")
            .setSmallIcon(Icon.createWithBitmap(original))
            .setLargeIcon(Icon.createWithBitmap(original))
            .setContentTitle("100 m")
            .setContentText("PRIVATE ROAD AND DESTINATION")
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .build()
        val sbn = notification("app.morphe.android.apps.maps", 1, 10, n)
        val zip = NavigationIconCapture.capture(context, listOf(sbn))
        try {
            assertTrue(zip.exists())
            assertTrue(zip.parentFile!!.name == "reports")
            ZipFile(zip).use { archive ->
                val text = archive.getInputStream(archive.getEntry("manifest.json")).bufferedReader().readText()
                assertFalse(text.contains("PRIVATE ROAD AND DESTINATION"))
                val item = JSONObject(text).getJSONArray("notifications").getJSONObject(0)
                assertEquals("app.morphe.android.apps.maps", item.getString("package"))
                for (kind in listOf("smallIcon", "largeIcon")) {
                    val icon = item.getJSONObject(kind)
                    assertEquals("exported", icon.getString("status"))
                    assertTrue(icon.getInt("width") in 1..256)
                    assertTrue(icon.getInt("height") in 1..256)
                    if (kind == "smallIcon") {
                        assertEquals(400, icon.getInt("originalWidth"))
                        assertEquals(256, icon.getInt("width"))
                        assertEquals(128, icon.getInt("height"))
                    }
                    val decoded = archive.getInputStream(archive.getEntry(icon.getString("file"))).use {
                        BitmapFactory.decodeStream(it)
                    }
                    assertNotNull(decoded)
                    assertEquals(icon.getInt("width"), decoded.width)
                    assertEquals(icon.getInt("height"), decoded.height)
                    decoded.recycle()
                }
            }
            assertFalse(original.isRecycled)
        } finally { zip.delete(); original.recycle() }
    }

    @Test fun `missing large icons are reported explicitly without inventing a maneuver image`() {
        val zip = NavigationIconCapture.capture(context,
            listOf(notification("app.morphe.android.apps.maps", 1, 10)))
        try {
            ZipFile(zip).use { archive ->
                val manifest = JSONObject(archive.getInputStream(archive.getEntry("manifest.json")).bufferedReader().readText())
                assertEquals("absent", manifest.getJSONArray("notifications").getJSONObject(0)
                    .getJSONObject("largeIcon").getString("status"))
                assertEquals(1, archive.size())
            }
        } finally { zip.delete() }
    }

    @Test fun `URI icons are neither loaded nor disclosed in the manifest`() {
        val n = Notification.Builder(context, "navigation")
            .setSmallIcon(Icon.createWithContentUri("content://private/SECRET_IMAGE_PATH"))
            .setCategory(Notification.CATEGORY_NAVIGATION).build()
        val zip = NavigationIconCapture.capture(context,
            listOf(notification("app.morphe.android.apps.maps", 1, 10, n)))
        try {
            ZipFile(zip).use { archive ->
                val text = archive.getInputStream(archive.getEntry("manifest.json")).bufferedReader().readText()
                assertFalse(text.contains("SECRET_IMAGE_PATH"))
                assertEquals("unsupported_type", JSONObject(text).getJSONArray("notifications").getJSONObject(0)
                    .getJSONObject("smallIcon").getString("status"))
                assertEquals(1, archive.size())
            }
        } finally { zip.delete() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `no Maps guidance cannot produce an empty successful export`() {
        NavigationIconCapture.capture(context, listOf(notification("com.waze", 1, 10)))
    }

    private fun notification(pkg: String, id: Int, time: Long, n: Notification = Notification()): StatusBarNotification {
        if (n.category == null) {
            n.flags = Notification.FLAG_ONGOING_EVENT
            n.category = Notification.CATEGORY_NAVIGATION
        }
        return StatusBarNotification(pkg, pkg, id, "test", 10_000, 20_000, 0, n,
            android.os.Process.myUserHandle(), time)
    }
}
