package com.byd.dashcast.satellite

import android.app.Application
import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class NativeVideoReceiverLifecycleTest {
    private class Listener : SatelliteVideoReceiver.Listener {
        val errors = mutableListOf<String>()
        val replies = mutableListOf<String>()
        val images = mutableListOf<Boolean>()
        override fun ready() = Unit
        override fun failed(code: String) { errors += code }
        override fun status(reference: Boolean, rendered: Boolean) { images += rendered }
        override fun reply(session: String, raw: String) { replies += raw }
    }
    private fun offer(id: String) = JSONObject().put("type", "video.offer").put("negotiation", id).put("sdp", "v=0\r\nm=video 9 RTP/SAVPF 96")
    private fun idle(millis: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    @Test fun `blocked native operation cannot prevent deadline blanking or invalidate a later attempt`() {
        val work = ArrayDeque<() -> Unit>()
        val listener = Listener()
        val receiver = NativeVideoReceiver(RuntimeEnvironment.getApplication(), listener, work::addLast)
        receiver.start() // Intentionally leave all native allocation blocked in this deterministic dispatcher.
        receiver.signal("session", offer("A"))
        idle(30_001)
        assertEquals(listOf("video_timeout"), listener.errors)
        assertTrue(listener.images.isNotEmpty()); assertFalse(listener.images.last())
        receiver.signal("session", offer("B"))
        idle(1_000)
        assertEquals(1, listener.errors.size)
        receiver.close(); idle()
    }
    @Test fun `a queued failure cannot escape after replacement then reset ABA`() {
        val work = ArrayDeque<() -> Unit>()
        val listener = Listener()
        val receiver = NativeVideoReceiver(RuntimeEnvironment.getApplication(), listener, work::addLast)
        receiver.start(); receiver.signal("session", offer("A"))
        // Expire A directly without draining the main callback posted by fail().
        val active = receiver.javaClass.getDeclaredField("active").apply { isAccessible = true }.get(receiver)
        val fail = receiver.javaClass.declaredMethods.single { it.name == "fail" }.apply { isAccessible = true }
        fail.invoke(receiver, active, "video_timeout")
        receiver.signal("session", offer("B")); receiver.reset()
        idle()
        assertTrue(listener.replies.isEmpty()); assertTrue(listener.errors.isEmpty())
        assertFalse(listener.images.last())
        receiver.close(); idle()
    }
    @Test fun `stop queues opaque waiting state and obsolete offer cannot survive a newer offer flood`() {
        val work = ArrayDeque<() -> Unit>()
        val listener = Listener()
        val receiver = NativeVideoReceiver(RuntimeEnvironment.getApplication(), listener, work::addLast)
        repeat(10_000) { receiver.signal("session", offer("attempt$it")) }
        assertEquals(1, work.size) // One drain owns the latest offer, rather than ten thousand 60 KiB payloads.
        idle()
        val active = receiver.javaClass.getDeclaredField("active").apply { isAccessible = true }.get(receiver)
        val renders = active.javaClass.getDeclaredField("rendered").apply { isAccessible = true }.get(active) as java.util.concurrent.atomic.AtomicLong
        renders.set(1)
        receiver.javaClass.declaredMethods.single { it.name == "tick" }.apply { isAccessible = true }.invoke(receiver, active)
        assertTrue(listener.images.last())
        receiver.signal("session", JSONObject().put("type", "video.stop").put("negotiation", "attempt9999"))
        idle()
        assertFalse(listener.images.last())
        renders.set(2); idle(1_000) // Late progress from the retired attempt cannot uncover the image.
        assertFalse(listener.images.last())
        receiver.close(); idle()
    }
    @Test fun `hotspot interfaces without Android Network handles remain discoverable without cellular media fallback`() {
        val options = NativeVideoReceiver.networkOptions()
        assertTrue(options.disableNetworkMonitor)
        assertEquals(0, options.networkIgnoreMask and org.webrtc.PeerConnectionFactory.Options.ADAPTER_TYPE_UNKNOWN)
        assertTrue(options.networkIgnoreMask and org.webrtc.PeerConnectionFactory.Options.ADAPTER_TYPE_CELLULAR != 0)
        assertTrue(options.networkIgnoreMask and org.webrtc.PeerConnectionFactory.Options.ADAPTER_TYPE_VPN != 0)
        assertTrue(options.networkIgnoreMask and org.webrtc.PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK != 0)
        assertEquals(SatelliteVideoDiagnostics.Event.NATIVE_RECEIVER_UNAVAILABLE, SatelliteVideoDiagnostics.failureEvent("native_receiver_unavailable"))
        assertEquals(SatelliteVideoDiagnostics.Event.HARDWARE_DECODER_UNAVAILABLE, SatelliteVideoDiagnostics.failureEvent("hardware_decoder_unavailable"))
        assertEquals(SatelliteVideoDiagnostics.Event.OTHER_FAILURE, SatelliteVideoDiagnostics.failureEvent("untrusted private text"))
    }
}
