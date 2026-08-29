package com.byd.dashcast.proxy.daemon

import com.byd.dashcast.proxy.daemon.HudBufferThrottlePolicy.MIN_INTERVAL_MS
import com.byd.dashcast.proxy.daemon.HudBufferThrottlePolicy.shouldRecord
import com.byd.dashcast.proxy.daemon.HudBufferThrottlePolicy.sinceSuffix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HudBufferThrottlePolicyTest {

    @Test
    fun `the first push from an id is always recorded`() {
        assertTrue(shouldRecord(intChanged = false, lastRecordedAtMs = 0L, nowMs = 10_000L))
    }

    @Test
    fun `a 25 Hz stream is cut to one line a second`() {
        // The shape of 0x99000198: a push every 40 ms, each with a different payload.
        var last = 1_000L
        var recorded = 0
        for (i in 1..250) {                       // 10 s of stream
            val now = 1_000L + i * 40L
            if (shouldRecord(intChanged = false, lastRecordedAtMs = last, nowMs = now)) {
                recorded++
                last = now
            }
        }
        assertEquals("one per second over ten seconds", 10, recorded)
    }

    @Test
    fun `a change of integer value is never throttled`() {
        // A state change is not a sample. Delaying one by up to a second would move the event
        // away from the marker a tester tapped to explain it.
        assertTrue(shouldRecord(intChanged = true, lastRecordedAtMs = 9_999L, nowMs = 10_000L))
    }

    @Test
    fun `exactly at the interval it records again`() {
        assertFalse(shouldRecord(false, 1_000L, 1_000L + MIN_INTERVAL_MS - 1))
        assertTrue(shouldRecord(false, 1_000L, 1_000L + MIN_INTERVAL_MS))
    }

    @Test
    fun `a clock that went backwards does not silence the log`() {
        assertTrue("a suspended CPU must not mute the recorder", shouldRecord(false, 50_000L, 10L))
    }

    @Test
    fun `the recorded line carries how many pushes it stands for`() {
        assertEquals("", sinceSuffix(0))
        assertEquals("", sinceSuffix(-1))
        assertEquals(" +24 since", sinceSuffix(24))
    }
}
