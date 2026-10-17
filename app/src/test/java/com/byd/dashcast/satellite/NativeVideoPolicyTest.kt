package com.byd.dashcast.satellite

import org.junit.Assert.*
import org.junit.Test

class NativeVideoPolicyTest {
    private fun candidate(host: String, protocol: String = "udp", kind: String = "host") = "candidate:1 1 $protocol 123 $host 5000 typ $kind"
    @Test fun `only private numeric IPv4 UDP host candidates survive`() {
        assertEquals("192.168.49.100", NativeVideoPolicy.candidate(candidate("192.168.49.100")))
        for (host in listOf("fe80::1", "8.8.8.8", "127.0.0.1", "0.0.0.0", "192.168.049.100", "peer.local"))
            assertNull(host, NativeVideoPolicy.candidate(candidate(host)))
        assertNull(NativeVideoPolicy.candidate(candidate("192.168.1.2", "tcp")))
        assertNull(NativeVideoPolicy.candidate(candidate("192.168.1.2", kind = "relay")))
        assertNull(NativeVideoPolicy.candidate(candidate("192.168.1.2") + " raddr 8.8.8.8 rport 5000"))
    }
    @Test fun `SDP filters embedded candidates to the authenticated socket peer and normalizes connection address`() {
        val sdp = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\nc=IN IP6 ::\r\na=${candidate("192.168.49.100")}\r\na=${candidate("192.168.51.101")}\r\na=${candidate("fe80::1")}\r\n"
        val filtered = NativeVideoPolicy.sdp(sdp, setOf("192.168.49.100"))
        assertTrue(filtered.contains("c=IN IP4 0.0.0.0"))
        assertTrue(filtered.contains("192.168.49.100"))
        assertFalse(filtered.contains("192.168.51.101")); assertFalse(filtered.contains("fe80"))
        for (invalid in listOf("", sdp + "m=audio 9 RTP/SAVPF 0", "a".repeat(60_001))) {
            try { NativeVideoPolicy.sdp(invalid, emptySet()); fail("Invalid SDP accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun `only actual renders refresh first-image and stall deadlines`() {
        val noAnswer = NativeImageWatchdog(100)
        assertFalse(noAnswer.expired(30_099)); assertTrue(noAnswer.expired(30_100))
        val answered = NativeImageWatchdog(100)
        answered.answered(500); answered.answered(10_000)
        assertFalse(answered.expired(15_499)); assertTrue(answered.expired(15_500))
        answered.rendered(15_400)
        assertFalse(answered.expired(21_399)); assertTrue(answered.expired(21_400))
    }
    @Test fun `inbox is bounded before dispatch and latest offer preempts old payloads without a second consumer`() {
        val inbox = NativeSignalInbox<String>()
        assertEquals(NativeSignalInbox.Result.SCHEDULE, inbox.submit("offer-A"))
        repeat(31) { assertEquals(NativeSignalInbox.Result.QUEUED, inbox.submit("ice-$it")) }
        assertEquals(NativeSignalInbox.Result.OVERFLOW, inbox.submit("excess"))
        assertEquals(32, inbox.size())
        assertEquals(NativeSignalInbox.Result.QUEUED, inbox.submit("offer-B", replace = true))
        assertEquals(1, inbox.size()); assertEquals("offer-B", inbox.take())
        inbox.clear(); assertNull(inbox.take())
        assertEquals(NativeSignalInbox.Result.SCHEDULE, inbox.submit("offer-C"))
    }
    @Test fun `borrowed track notification never retains an SDK object and resolves the current owned receiver once on the worker`() {
        val pending = ArrayDeque<() -> Unit>()
        var callbackReturned = false; var lookups = 0; var current = true
        val notice = NativeTrackNotification({ current }, pending::addLast) {
            assertTrue("Receiver lookup must run after the borrowed callback returns", callbackReturned)
            lookups++
        }
        notice.received(); notice.received()
        assertEquals(0, lookups); assertEquals(1, pending.size)
        callbackReturned = true
        pending.removeFirst()(); notice.received()
        assertEquals(1, lookups); assertTrue(pending.isEmpty())
        val retired = NativeTrackNotification({ current }, pending::addLast) { fail("Retired peer lookup") }
        retired.received(); current = false; pending.removeFirst()()
        assertEquals(1, lookups)
    }
}
