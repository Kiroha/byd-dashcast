package com.byd.dashcast.satellite

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.byd.dashcast.R
import org.json.JSONObject
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
class SatelliteVideoBootstrapTest {
    private class Receiver(context: Context, val callbacks: SatelliteVideoReceiver.Listener) : SatelliteVideoReceiver {
        override val view = View(context)
        var starts = 0; var closes = 0; var resets = 0; var signals = 0
        override fun start() { starts++ }
        override fun close() { closes++ }
        override fun reset() { resets++ }
        override fun signal(session: String, message: JSONObject) { signals++ }
    }
    private lateinit var controller: ActivityController<SatelliteVideoActivity>
    private lateinit var activity: SatelliteVideoActivity
    private val receivers = mutableListOf<Receiver>()
    private val messages = mutableListOf<String>()
    private val production = SatelliteVideoActivity.receiverFactory
    @Before fun open() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(SatellitePrefs.FILE, 0).edit().clear().commit()
        SatellitePrefs.setEnabled(context, true)
        SatelliteVideoActivity.receiverFactory = { ctx, callbacks -> Receiver(ctx, callbacks).also(receivers::add) }
        SatelliteVideoHub.connect("bootstrap-test") { messages += JSONObject(it).getString("type") }
        controller = Robolectric.buildActivity(SatelliteVideoActivity::class.java).setup()
        activity = controller.get()
    }
    @After fun close() {
        controller.pause().stop().destroy()
        SatelliteVideoActivity.receiverFactory = production
        SatelliteVideoHub.disconnect("bootstrap-test")
        SatellitePrefs.setEnabled(RuntimeEnvironment.getApplication(), false)
    }
    private fun idle(millis: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    private fun label() = activity.window.decorView.findViewWithTag<TextView>("satellite_video_state")
    private fun retry() = activity.window.decorView.findViewWithTag<Button>("satellite_video_retry")
    @Test fun `missing bootstrap callback releases partial resources and never advertises viewer`() {
        val first = receivers.single()
        idle(SatelliteVideoActivity.STARTUP_TIMEOUT_MS)
        assertEquals(1, first.closes)
        assertEquals(activity.getString(R.string.satellite_native_failed), label().text.toString())
        assertEquals(View.VISIBLE, retry().visibility)
        first.callbacks.ready()
        assertFalse(messages.contains("video.ready"))
    }
    @Test fun `ready cancels startup deadline and duplicate ready is ignored`() {
        receivers.single().callbacks.ready()
        receivers.single().callbacks.ready()
        idle(SatelliteVideoActivity.STARTUP_TIMEOUT_MS + 1)
        assertEquals(1, messages.count { it == "video.ready" })
        assertEquals(0, receivers.single().closes)
    }
    @Test fun `hardware absence is visible and retry fences the old bootstrap`() {
        val first = receivers.single()
        first.callbacks.failed("hardware_decoder_unavailable")
        assertEquals(activity.getString(R.string.satellite_native_no_hardware), label().text.toString())
        assertEquals(1, first.closes)
        retry().performClick()
        first.callbacks.ready(); first.callbacks.failed("native_receiver_unavailable")
        assertFalse(messages.contains("video.ready"))
        receivers.last().callbacks.ready()
        assertEquals(1, messages.count { it == "video.ready" })
    }
    @Test fun `CarPlay hide releases immediately and resume creates a new receiver without accepting old frames or replies`() {
        val first = receivers.single()
        first.callbacks.ready()
        controller.pause().stop()
        assertEquals(1, first.closes)
        assertEquals(1, messages.count { it == "video.closed" })
        controller.start().resume().visible()
        val second = receivers.last()
        assertNotSame(first, second)
        first.callbacks.ready(); first.callbacks.status(false, true)
        first.callbacks.reply("bootstrap-test", "{\"type\":\"video.answer\"}")
        assertEquals(0, messages.count { it == "video.answer" })
        second.callbacks.ready()
        assertEquals(2, messages.count { it == "video.ready" })
    }
    @Test fun `control reset invalidates the native receiver synchronously without waiting for main delivery`() {
        val current = receivers.single()
        current.callbacks.ready()
        activity.signal("bootstrap-test", JSONObject().put("type", "video.offer"))
        activity.reset()
        idle()
        assertEquals(1, current.signals)
        assertTrue(current.resets > 0)
    }
    @Test fun `disabling receiver closes destination guard without altering guidance`() {
        receivers.single().callbacks.ready()
        SatellitePrefs.setEnabled(activity, false)
        idle(500)
        assertTrue(activity.isFinishing)
    }
}
