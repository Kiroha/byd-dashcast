package com.byd.dashcast.satellite

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SatelliteVideoDiagnosticsTest {
    @Test fun `bounded timeline retains only enum evidence and scalar totals`() {
        var now = 1000L
        val history = SatelliteVideoDiagnostics.Timeline { now }
        repeat(100) { history.record(SatelliteVideoDiagnostics.Event.VIEWER_READY) }
        repeat(200) { history.record(SatelliteVideoDiagnostics.Event.ICE_RECEIVED, recent = false) }
        now = 6000
        val text = history.summary()
        assertTrue(text.contains("viewer_ready=100"))
        assertTrue(text.contains("ice_received=200"))
        assertEquals(32, text.lineSequence().count { it.startsWith("5s ago: viewer_ready") })
        assertTrue(text.length < 4000)
    }

    @Test fun `recent events are newest first and queued replies are not claimed transmitted`() {
        var now = 1000L
        val history = SatelliteVideoDiagnostics.Timeline { now }
        history.record(SatelliteVideoDiagnostics.Event.OFFER_RECEIVED)
        now = 2000
        history.record(SatelliteVideoDiagnostics.Event.ANSWER_QUEUED)
        assertEquals(listOf("0s ago: answer_queued", "1s ago: offer_received"),
            history.summary().lines().filter { it.contains("s ago:") })
        assertTrue(history.summary().contains("queued replies do not confirm transmission"))
        assertFalse(history.summary().contains("answer_sent"))
    }

    @Test fun `ICE totals saturate while timeline remains empty`() {
        val history = SatelliteVideoDiagnostics.Timeline { 0L }
        repeat(1_000_005) { history.record(SatelliteVideoDiagnostics.Event.ICE_QUEUED, recent = false) }
        assertTrue(history.summary().contains("ice_queued=1000000"))
        assertFalse(history.summary().contains("1000005"))
        assertFalse(history.summary().contains("s ago:"))
    }
    @Test fun `untrusted diagnostic input cannot retain payload text`() {
        val payload = "private SDP notification token 192.168.49.1"
        SatelliteVideoDiagnostics.progress(payload)
        SatelliteVideoDiagnostics.failure(payload)
        assertFalse(SatelliteVideoDiagnostics.summary().contains(payload))
        assertTrue(SatelliteVideoDiagnostics.summary().contains("other_failure"))
    }
}
