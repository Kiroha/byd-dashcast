package com.byd.dashcast.util.concurrent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationGateTest {
    @Test
    fun `invalidation rejects old work and permits new work`() {
        val gate = GenerationGate()
        val old = gate.capture()

        gate.invalidate()
        val current = gate.capture()

        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(current))
    }

    @Test
    fun `each lifecycle transition invalidates every earlier generation`() {
        val gate = GenerationGate()
        val first = gate.capture()
        gate.invalidate()
        val second = gate.capture()
        gate.invalidate()

        assertFalse(gate.isCurrent(first))
        assertFalse(gate.isCurrent(second))
        assertTrue(gate.isCurrent(gate.capture()))
    }
}