package com.byd.dashcast.satellite

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.byd.dashcast.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SatelliteSettingsStatusTest {
    private lateinit var context: Application
    private var controller: ActivityController<SatelliteSettingsActivity>? = null

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(SatellitePrefs.FILE, 0).edit().clear().commit()
        SatellitePairingSession.close()
    }
    @After fun tearDown() { controller?.pause()?.stop()?.destroy(); SatellitePairingSession.close() }

    private fun open(): SatelliteSettingsActivity {
        controller = Robolectric.buildActivity(SatelliteSettingsActivity::class.java).setup()
        return controller!!.get()
    }
    private fun row(activity: SatelliteSettingsActivity, id: Int) = activity.findViewById<ViewGroup>(id)
    private fun icon(row: ViewGroup): ImageView = (row.getChildAt(1) as ViewGroup).getChildAt(0) as ImageView
    private fun assertLabel(activity: SatelliteSettingsActivity, id: Int, category: Int, text: Int) {
        val row = row(activity, id)
        assertEquals(activity.getString(R.string.satellite_status_accessibility,
            activity.getString(category), activity.getString(text)), row.contentDescription)
        assertTrue(row.isScreenReaderFocusable)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, row.accessibilityLiveRegion)
        assertNotNull(icon(row).drawable)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon(row).importantForAccessibility)
    }

    @Test fun `disabled rows have named accessible labels and a grey icon`() {
        val activity = open()
        assertLabel(activity, R.id.satellite_connection_status, R.string.satellite_status_connection,
            R.string.satellite_connection_disabled)
        assertLabel(activity, R.id.satellite_guidance_status, R.string.satellite_status_guidance,
            R.string.satellite_guidance_disabled)
        val tint = icon(row(activity, R.id.satellite_connection_status)).imageTintList!!.defaultColor
        assertEquals(ContextCompat.getColor(activity, R.color.md_on_surface_variant), tint)
    }

    @Test fun `visible page refreshes on listening authentication guidance expiry and revocation`() {
        NavigationInputRouter.enableReceiver(context, true); NavigationInputRouter.selectRemote(context, true)
        val owner = SatelliteStatus.begin()
        val activity = open()
        SatelliteStatus.listening(owner)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertLabel(activity, R.id.satellite_connection_status, R.string.satellite_status_connection,
            R.string.satellite_connection_waiting)
        SatelliteStatus.connected(owner, "private-peer-marker")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertLabel(activity, R.id.satellite_connection_status, R.string.satellite_status_connection,
            R.string.satellite_connection_connected)
        assertLabel(activity, R.id.satellite_guidance_status, R.string.satellite_status_guidance,
            R.string.satellite_guidance_waiting)
        SatelliteStatus.guidance(owner, "private-peer-marker", SystemClock.elapsedRealtime(), 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertLabel(activity, R.id.satellite_guidance_status, R.string.satellite_status_guidance,
            R.string.satellite_guidance_active)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SatelliteProtocol.NAV_TIMEOUT_MS))
        assertLabel(activity, R.id.satellite_guidance_status, R.string.satellite_status_guidance,
            R.string.satellite_guidance_expired)
        SatellitePrefs.rotateToken(context)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertLabel(activity, R.id.satellite_connection_status, R.string.satellite_status_connection,
            R.string.satellite_connection_waiting)
        assertFalse(row(activity, R.id.satellite_connection_status).contentDescription.contains("private-peer-marker"))
    }

    @Test fun `returning to the page immediately shows expired background guidance without altering receiver facts`() {
        NavigationInputRouter.enableReceiver(context, true); NavigationInputRouter.selectRemote(context, true)
        val owner = SatelliteStatus.begin(); SatelliteStatus.connected(owner, "peer")
        SatelliteStatus.guidance(owner, "peer", SystemClock.elapsedRealtime(), 0)
        val activity = open()
        controller!!.pause().stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SatelliteProtocol.NAV_TIMEOUT_MS))
        assertEquals(SatelliteStatusTracker.Connection.CONNECTED, SatelliteStatus.snapshot(context).connection)
        controller!!.start().resume()
        assertLabel(activity, R.id.satellite_connection_status, R.string.satellite_status_connection,
            R.string.satellite_connection_connected)
        assertLabel(activity, R.id.satellite_guidance_status, R.string.satellite_status_guidance,
            R.string.satellite_guidance_expired)
    }

    @Test @Config(qualifiers = "night") fun `status icons use the night theme semantic palette`() {
        NavigationInputRouter.enableReceiver(context, true)
        val owner = SatelliteStatus.begin(); SatelliteStatus.connected(owner, "peer")
        val activity = open()
        val tint = icon(row(activity, R.id.satellite_connection_status)).imageTintList!!.defaultColor
        assertEquals(ContextCompat.getColor(activity, R.color.md_status_ok), tint)
        assertEquals(0xff3cd070.toInt(), tint)
        assertLabel(activity, R.id.satellite_connection_status, R.string.satellite_status_connection,
            R.string.satellite_connection_connected)
    }
}
