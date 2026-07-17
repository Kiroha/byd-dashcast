package com.byd.dashcast.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTelemetryGateTest {
    @Test
    fun `tracks overlapping visible subscribers`() {
        val gate = VoiceTelemetryGate()

        assertFalse(gate.isEnabled())
        gate.acquire()
        gate.acquire()
        assertTrue(gate.isEnabled())
        gate.release()
        assertTrue(gate.isEnabled())
        gate.release()
        assertFalse(gate.isEnabled())
    }

    @Test
    fun `extra release cannot make future subscriber invisible`() {
        val gate = VoiceTelemetryGate()

        gate.release()
        gate.acquire()

        assertTrue(gate.isEnabled())
    }
}