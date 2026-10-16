package com.byd.dashcast.satellite

import android.app.Application
import org.junit.Assert.*
import org.json.JSONObject
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

    private fun observation() = JSONObject().put("statsState", "completed")
        .put("trackReceived", true).put("mediaConnected", true).put("decoded", false)

    @Test fun `receiver scalar schema rejects coercions and never retains arbitrary keys or payload text`() {
        val raw = observation().put("bytesReceived", "42").put("packetsReceived", true)
            .put("framesDecoded", -1).put("keyFramesDecoded", 1.5).put("startupAgeMs", 86_400_001)
            .put("framesReceived", 9_000_000_000_001L).put("codec", "private SDP")
            .put("private track id", "private route token 192.168.49.1").put("webkitDecoded", 0)
        val parsed = SatelliteVideoDiagnostics.Observation.parse(raw.toString())!!
        assertNull(parsed.codec)
        for (key in listOf("bytesReceived", "packetsReceived", "framesDecoded", "keyFramesDecoded", "startupAgeMs", "framesReceived"))
            assertNull(parsed.counters[key])
        assertEquals(0L, parsed.counters["webkitDecoded"])
        assertFalse(parsed.summary().contains("private"))
        assertFalse(parsed.summary().contains("192.168"))
        assertTrue(parsed.summary().contains("framesDropped=?"))
        assertNull(SatelliteVideoDiagnostics.Observation.parse(raw.put("statsState", "arbitrary message").toString()))
        assertNull(SatelliteVideoDiagnostics.Observation.parse(observation().put("decoded", "true").toString()))
        assertNull(SatelliteVideoDiagnostics.Observation.parse("x".repeat(2049)))
        assertNull(SatelliteVideoDiagnostics.Observation.parse("not JSON"))
    }

    @Test fun `provider evidence accepts only bounded numeric versions`() {
        SatelliteVideoDiagnostics.providerVersion("74.0.3729.185")
        assertTrue(SatelliteVideoDiagnostics.summary().contains("WebView version=74.0.3729.185"))
        SatelliteVideoDiagnostics.providerVersion("private token 192.168.49.1")
        assertTrue(SatelliteVideoDiagnostics.summary().contains("WebView version=?"))
        assertFalse(SatelliteVideoDiagnostics.summary().contains("private token"))
        SatelliteVideoDiagnostics.providerVersion("...")
        assertTrue(SatelliteVideoDiagnostics.summary().contains("WebView version=?"))
        SatelliteVideoDiagnostics.providerVersion("1".repeat(41))
        assertTrue(SatelliteVideoDiagnostics.summary().contains("WebView version=?"))
    }

    @Test fun `counter bounds codec whitelist and nullable fields survive without claiming display`() {
        val parsed = SatelliteVideoDiagnostics.Observation.parse(observation()
            .put("codec", "H264").put("bytesReceived", 9_000_000_000_000L)
            .put("framesDecoded", 3).put("answerAgeMs", 15_000).toString())!!
        assertEquals("H264", parsed.codec)
        assertEquals(9_000_000_000_000L, parsed.counters["bytesReceived"])
        assertEquals(3L, parsed.counters["framesDecoded"])
        assertEquals(15_000L, parsed.counters["answerAgeMs"])
        assertNull(parsed.counters["packetsReceived"])
        assertFalse(parsed.decoded)
    }

    @Test fun `attempt retention is bounded rate limited and survives closure with historical caveat`() {
        var now = 0L
        val history = SatelliteVideoDiagnostics.Attempts { now }
        val parsed = SatelliteVideoDiagnostics.Observation.parse(observation().put("bytesReceived", 1).toString())!!
        val second = SatelliteVideoDiagnostics.Observation.parse(observation().put("bytesReceived", 2).toString())!!
        val first = history.begin()
        history.observe(first, parsed)
        now = 500
        history.observe(first, second)
        assertTrue(history.summary().contains("bytesReceived=1"))
        now = 1000
        history.observe(first, second)
        history.end(first, SatelliteVideoDiagnostics.End.VIEWER_HIDDEN)
        now = 2000
        history.observe(first, parsed)
        assertTrue(history.summary().contains("bytesReceived=2"))
        assertTrue(history.summary().contains("end=viewer_hidden"))
        assertTrue(history.summary().contains("sample_age_ms=1000"))
        repeat(20) { history.begin() }
        val text = history.summary()
        assertEquals(8, text.lines().count { it.startsWith("attempt=") })
        assertFalse(text.contains("attempt=1 "))
        assertTrue(text.contains("Historical decode counters do not prove current display"))
        assertTrue(text.length < 10000)
    }

}
