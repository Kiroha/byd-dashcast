package com.byd.dashcast.cluster

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import com.byd.dashcast.data.prefs.ClusterPrefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class NativeAdasWindowAndroidTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun receiver(enabled: Boolean = true, exported: Boolean = true, permission: String? = null) {
        val info = ActivityInfo().apply {
            packageName = "com.byd.clusterdebug"
            name = "com.byd.clusterdebug.BroadcastReceiverCAN"
            this.enabled = enabled; this.exported = exported; this.permission = permission
            applicationInfo = ApplicationInfo().apply { packageName = "com.byd.clusterdebug"; this.enabled = true }
        }
        shadowOf(context.packageManager).addOrUpdateReceiver(info)
    }
    @Test fun `only positively identified researched firmware passes the platform gate`() {
        assertTrue(AndroidDevice.supportsFirmware(29, true, AndroidDevice.KNOWN_FINGERPRINT))
        assertFalse(AndroidDevice.supportsFirmware(29, false, AndroidDevice.KNOWN_FINGERPRINT))
        assertFalse(AndroidDevice.supportsFirmware(28, true, AndroidDevice.KNOWN_FINGERPRINT))
        assertFalse(AndroidDevice.supportsFirmware(31, true, AndroidDevice.KNOWN_FINGERPRINT))
        assertFalse(AndroidDevice.supportsFirmware(29, true, "different-firmware"))
    }

    @Test fun `fallback platform and inaccessible or missing receiver refuse capability`() {
        assertFalse(AndroidDevice(context) { true }.available())
        receiver()
        assertFalse(AndroidDevice(context) { false }.available())
        assertTrue(AndroidDevice(context) { true }.available())
        receiver(exported = false)
        assertFalse(AndroidDevice(context) { true }.available())
        receiver(enabled = false)
        assertFalse(AndroidDevice(context) { true }.available())
        receiver(permission = "vendor.private.permission")
        assertFalse(AndroidDevice(context) { true }.available())
    }
    @Test fun `only allowlisted frames target the explicit receiver and no service is started`() {
        val device = AndroidDevice(context) { true }
        val before = shadowOf(context).broadcastIntents.size
        device.send(NativeAdasWindowControl.HIDE_FRAME)
        device.send(NativeAdasWindowControl.RESTORE_FRAME)
        val sent = shadowOf(context).broadcastIntents.drop(before)
        assertEquals(2, sent.size)
        for (intent in sent) {
            assertEquals(NativeAdasWindowControl.RECEIVER, intent.component)
            assertEquals("com.byd.cluster.spi", intent.action)
            assertTrue(intent.flags and Intent.FLAG_INCLUDE_STOPPED_PACKAGES != 0)
            assertEquals(setOf("wholeFrame"), intent.extras!!.keySet())
        }
        assertEquals("23,50,00,08,01,00", sent[0].getStringExtra("wholeFrame"))
        assertEquals("23,50,00,08,01,01", sent[1].getStringExtra("wholeFrame"))
        try { device.send("23,50,00,08,01,02"); fail() } catch (_: IllegalArgumentException) { }
        assertNull(shadowOf(context).nextStartedService)
    }
    @Test fun `recovery survives reopening only for the captured firmware`() {
        val prefs = context.getSharedPreferences(ClusterPrefs.PREFS_NAME, 0)
        prefs.edit().clear().commit()
        assertNull(ClusterPrefs.nativeAdasRestoreValue(context))
        assertTrue(ClusterPrefs.saveNativeAdasRestoreValue(context))
        assertEquals(1, ClusterPrefs.nativeAdasRestoreValue(context.applicationContext))
        prefs.edit().putString("native_adas_restore_firmware", "different-firmware").commit()
        assertNull(ClusterPrefs.nativeAdasRestoreValue(context))
    }
}
