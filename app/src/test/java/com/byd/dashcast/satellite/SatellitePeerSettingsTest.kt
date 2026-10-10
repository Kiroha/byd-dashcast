package com.byd.dashcast.satellite

import android.app.Application
import android.os.Looper
import android.widget.TextView
import com.byd.dashcast.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SatellitePeerSettingsTest {
    private lateinit var context: Application
    private var controller: ActivityController<SatelliteSettingsActivity>? = null
    private val device = SatellitePeerIdentity("12345678-1234-4123-8123-123456789abc", "Carlinkit Tbox Ultra")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(SatellitePrefs.FILE, 0).edit().clear().commit()
        SatellitePeers.clear(context, history = true)
        SatellitePrefs.setEnabled(context, true)
    }
    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        SatellitePrefs.setEnabled(context, false)
        SatellitePeers.clear(context, history = true)
    }
    private fun open(): SatelliteSettingsActivity {
        controller = Robolectric.buildActivity(SatelliteSettingsActivity::class.java).setup()
        return controller!!.get()
    }
    private fun text(activity: SatelliteSettingsActivity) = activity.window.decorView
        .findViewWithTag<TextView>("satellite_peer_identity").text.toString()

    @Test fun `settings start without inventing a paired device`() {
        SatelliteStatus.begin()
        val activity = open()
        assertEquals(activity.getString(R.string.satellite_device_none), text(activity))
    }

    @Test fun `visible page distinguishes current device from disconnected history and revoke`() {
        val owner = SatelliteStatus.begin()
        val token = SatellitePrefs.token(context)
        val activity = open()
        SatellitePeers.authenticated(context, token, "live", device, "192.168.43.2")
        SatelliteStatus.connected(owner, "live")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue(text(activity).startsWith(activity.getString(R.string.satellite_device_connected, device.name)))
        assertTrue(text(activity).contains(device.id))
        assertTrue(text(activity).contains("192.168.43.2"))
        assertFalse(text(activity).contains(token))
        SatellitePeers.disconnected("live")
        SatelliteStatus.disconnected(owner, "live")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue(text(activity).contains(device.name))
        assertFalse(text(activity).startsWith(activity.getString(R.string.satellite_device_connected, device.name)))
        SatellitePrefs.rotateToken(context)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(activity.getString(R.string.satellite_device_none), text(activity))
    }

    @Test fun `metadata refreshes when connected status remains unchanged`() {
        val owner = SatelliteStatus.begin()
        val token = SatellitePrefs.token(context)
        SatellitePeers.authenticated(context, token, "first", device, "192.168.43.2")
        SatelliteStatus.connected(owner, "first")
        val activity = open()
        SatellitePeers.authenticated(context, token, "legacy", null, "192.168.43.3")
        SatelliteStatus.connected(owner, "legacy")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue(text(activity).contains(activity.getString(R.string.satellite_device_unknown)))
        assertTrue(text(activity).contains("192.168.43.3"))
        assertFalse(text(activity).contains(device.name))
        assertFalse(text(activity).contains(device.id))
    }
}
