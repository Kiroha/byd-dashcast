package com.byd.dashcast.satellite

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.RenderProcessGoneDetail
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

/** Native lifecycle tests; the separate emulator probe executes the actual bundled JavaScript. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SatelliteVideoBootstrapTest {
    private lateinit var controller: ActivityController<SatelliteVideoActivity>
    private lateinit var activity: SatelliteVideoActivity
    private val messages = mutableListOf<String>()

    @Before fun open() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(SatellitePrefs.FILE, 0).edit().clear().commit()
        SatellitePrefs.setEnabled(context, true)
        SatelliteVideoHub.connect("bootstrap-test") { messages += JSONObject(it).getString("type") }
        controller = Robolectric.buildActivity(SatelliteVideoActivity::class.java).setup()
        activity = controller.get()
    }

    @After fun close() {
        controller.pause().stop().destroy()
        SatelliteVideoHub.disconnect("bootstrap-test")
        SatellitePrefs.setEnabled(RuntimeEnvironment.getApplication(), false)
    }

    private fun label() = activity.window.decorView.findViewWithTag<TextView>("satellite_video_state")
    private fun retry() = activity.window.decorView.findViewWithTag<Button>("satellite_video_retry")
    private fun webView(): WebView {
        fun find(view: View): WebView? {
            if (view is WebView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return find(activity.window.decorView)!!
    }
    private fun bridge() = shadowOf(webView()).getJavascriptInterface("DashCastVideo") as SatelliteVideoActivity.Bridge
    private fun idle(millis: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    private fun assertFailure() {
        assertEquals(activity.getString(R.string.satellite_video_unavailable), label().text.toString())
        assertEquals(View.VISIBLE, retry().visibility)
        assertFalse(activity.isFinishing)
    }

    @Test fun `no JavaScript callback produces visible bounded failure without advertising a viewer`() {
        assertEquals(activity.getString(R.string.satellite_video_loading), label().text.toString())
        idle(SatelliteVideoActivity.STARTUP_TIMEOUT_MS)
        assertFailure()
        assertFalse(messages.contains("video.ready"))
    }

    @Test fun `successful bootstrap cancels its deadline and ignores duplicate ready callbacks`() {
        val current = bridge()
        current.ready(true); idle()
        current.ready(true); idle(SatelliteVideoActivity.STARTUP_TIMEOUT_MS + 1)
        assertEquals(listOf("video.ready"), messages)
        assertEquals(View.GONE, (label().parent as View).visibility)
    }

    @Test fun `unsupported WebRTC fails visibly and an old success cannot revive it`() {
        val old = bridge()
        old.ready(false); idle()
        assertFailure()
        old.ready(true); idle()
        assertFalse(messages.contains("video.ready"))
    }

    @Test fun `retry fences old bridges and the previous attempt deadline`() {
        val old = bridge()
        idle(6_000)
        old.failed("script_error"); idle()
        retry().performClick()
        val replacement = bridge()
        old.ready(true); old.failed("script_error"); idle(4_001)
        assertEquals(activity.getString(R.string.satellite_video_loading), label().text.toString())
        assertTrue(messages.isEmpty())
        replacement.ready(true); idle()
        assertEquals(listOf("video.ready"), messages)
    }

    @Test fun `error after ready closes the viewer and preserves visible retry`() {
        val current = bridge()
        current.ready(true); idle()
        current.failed("script_error"); idle()
        assertEquals(listOf("video.ready", "video.closed"), messages)
        assertFailure()
        current.ready(true); idle()
        assertEquals(2, messages.size)
    }

    @Test fun `background loading pauses its deadline and cannot advertise a hidden viewer`() {
        val current = bridge()
        controller.pause().stop()
        idle(SatelliteVideoActivity.STARTUP_TIMEOUT_MS + 1)
        assertEquals(activity.getString(R.string.satellite_video_loading), label().text.toString())
        current.ready(true); idle()
        assertTrue(messages.isEmpty())
        controller.start().resume(); idle()
        assertEquals(listOf("video.ready"), messages)
        controller.pause().stop(); idle()
        assertEquals(listOf("video.ready", "video.closed"), messages)
    }

    @Test fun `unknown JavaScript failure text is ignored and never shown`() {
        bridge().failed("private notification or SDP"); idle()
        assertEquals(activity.getString(R.string.satellite_video_loading), label().text.toString())
        assertTrue(messages.isEmpty())
    }

    @Test fun `renderer loss releases the old view and queued resets cannot touch its replacement`() {
        bridge().ready(true); idle()
        val old = webView()
        activity.signal("bootstrap-test", JSONObject().put("type", "video.stop"))
        activity.reset()
        shadowOf(old).webViewClient.onRenderProcessGone(old, object : RenderProcessGoneDetail() {
            override fun didCrash() = true
            override fun rendererPriorityAtExit() = 0
        })
        assertFailure()
        assertNull(old.parent)
        retry().performClick()
        val replacement = webView()
        idle()
        assertNull(shadowOf(replacement).lastEvaluatedJavascript)
        bridge().ready(true); idle()
        assertEquals(listOf("video.ready", "video.closed", "video.ready"), messages)
    }

    @Test fun `loading resumed without callback gets a new deadline after background time`() {
        idle(6_000)
        controller.pause().stop()
        idle(20_000)
        controller.start().resume()
        idle(4_001)
        assertEquals(activity.getString(R.string.satellite_video_loading), label().text.toString())
        idle(6_000)
        assertFailure()
        assertTrue(messages.isEmpty())
    }

    @Test fun `hidden and obsolete bridges cannot report decoded progress`() {
        val old = bridge()
        old.ready(true); idle()
        val before = SatelliteVideoDiagnostics.summary()
        controller.pause().stop()
        old.progress(0, "frames_decoded"); idle()
        val hidden = SatelliteVideoDiagnostics.summary()
        assertEquals(Regex("frames_decoded=([0-9]+)").find(before)?.value,
            Regex("frames_decoded=([0-9]+)").find(hidden)?.value)
        controller.start().resume()
        old.failed("script_error"); idle()
        retry().performClick()
        bridge().ready(true); idle()
        val current = SatelliteVideoDiagnostics.summary()
        old.progress(0, "frames_decoded"); old.progress(0, "private SDP"); idle()
        assertEquals(Regex("frames_decoded=([0-9]+)").find(current)?.value,
            Regex("frames_decoded=([0-9]+)").find(SatelliteVideoDiagnostics.summary())?.value)
        assertFalse(SatelliteVideoDiagnostics.summary().contains("private SDP"))
    }

    private fun offer(negotiation: String): Long {
        activity.signal("bootstrap-test", JSONObject().put("type", "video.offer").put("negotiation", negotiation))
        idle()
        return shadowOf(webView()).lastEvaluatedJavascript!!.substringAfterLast(',').removeSuffix(")").toLong()
    }
    private fun observation(bytes: Long = 7) = JSONObject()
        .put("statsState", "completed").put("bytesReceived", bytes)
        .put("trackReceived", true).put("mediaConnected", true).put("decoded", false).toString()
    private fun attemptLine(number: Long) = SatelliteVideoDiagnostics.summary().lines().single { it.startsWith("attempt=$number ") }

    @Test fun `same activity replacement peers fence old progress and queued observations`() {
        val current = bridge()
        current.ready(true); idle()
        val old = offer("old")
        val replacement = offer("new")
        current.observe(replacement, observation(100))
        current.observe(old, observation(999)) // Cannot replace the newer queued delivery.
        current.progress(old, "frames_decoded")
        current.send(old, "bootstrap-test", JSONObject().put("type", "video.error").put("code", "video_timeout").toString())
        idle()
        assertTrue(attemptLine(old).contains("end=replaced"))
        assertTrue(attemptLine(replacement).contains("bytesReceived=100"))
        assertTrue(attemptLine(replacement).contains("end=active"))
        assertFalse(messages.contains("video.error"))
    }

    @Test fun `current decoded stage and final timeout preserve typed receiver evidence`() {
        val current = bridge(); current.ready(true); idle()
        val number = offer("current")
        current.observe(number, observation(200)); idle()
        val before = Regex("frames_decoded=([0-9]+)").find(SatelliteVideoDiagnostics.summary())?.groupValues?.get(1)?.toInt() ?: 0
        current.progress(number, "frames_decoded"); idle()
        val after = Regex("frames_decoded=([0-9]+)").find(SatelliteVideoDiagnostics.summary())!!.groupValues[1].toInt()
        assertEquals(before + 1, after)
        current.send(number, "bootstrap-test", JSONObject().put("type", "video.error").put("code", "video_timeout").toString()); idle()
        assertTrue(messages.contains("video.error"))
        assertTrue(attemptLine(number).contains("end=video_timeout"))
        assertTrue(attemptLine(number).contains("bytesReceived=200"))
    }

    @Test fun `matching stop preserves last observation but old stop cannot retire the replacement`() {
        val current = bridge(); current.ready(true); idle()
        offer("old")
        val replacement = offer("new")
        activity.signal("bootstrap-test", JSONObject().put("type", "video.stop").put("negotiation", "old")); idle()
        current.observe(replacement, observation(77)); idle()
        assertTrue(attemptLine(replacement).contains("end=active"))
        assertTrue(attemptLine(replacement).contains("bytesReceived=77"))
        activity.signal("bootstrap-test", JSONObject().put("type", "video.stop").put("negotiation", "new")); idle()
        assertTrue(attemptLine(replacement).contains("end=sender_stopped"))
        assertTrue(attemptLine(replacement).contains("bytesReceived=77"))
    }

    @Test fun `observation bursts coalesce and hidden viewer cannot modify historical scalar evidence`() {
        val current = bridge(); current.ready(true); idle()
        val number = offer("current")
        repeat(100) { current.observe(number, observation(it.toLong())) }
        idle()
        assertTrue(attemptLine(number).contains("bytesReceived=99"))
        controller.pause().stop(); idle()
        current.observe(number, observation(999)); idle(2000)
        assertTrue(attemptLine(number).contains("end=viewer_hidden"))
        assertTrue(attemptLine(number).contains("bytesReceived=99"))
        assertFalse(attemptLine(number).contains("bytesReceived=999"))
    }

    @Test fun `obsolete webview bridges cannot replace current peer evidence or end its attempt`() {
        val old = bridge(); old.ready(true); idle()
        val oldNumber = offer("old")
        old.failed("script_error"); idle()
        retry().performClick()
        val current = bridge(); current.ready(true); idle()
        val number = offer("new")
        current.observe(number, observation(123))
        old.observe(oldNumber, observation(999))
        old.send(number, "bootstrap-test", JSONObject().put("type", "video.error").put("code", "video_timeout").toString())
        idle()
        assertTrue(attemptLine(number).contains("end=active"))
        assertTrue(attemptLine(number).contains("bytesReceived=123"))
    }

    @Test fun `forced terminal snapshot has priority over coalesced normal sample before error closes attempt`() {
        val current=bridge();current.ready(true);idle()
        val number=offer("terminal")
        current.observe(number,observation(1));idle()
        val terminal=JSONObject(observation(2)).put("playState","rejected").put("mediaError",3).toString()
        current.observeFinal(number,terminal)
        current.observe(number,observation(999))
        current.send(number,"bootstrap-test",JSONObject().put("type","video.error").put("code","playback_failed").toString())
        idle()
        assertTrue(attemptLine(number).contains("bytesReceived=2"))
        assertTrue(attemptLine(number).contains("playState=rejected"))
        assertTrue(attemptLine(number).contains("end=playback_failed"))
    }

}
