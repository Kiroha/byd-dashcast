package com.byd.dashcast.util.concurrent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleGateTest {

    @Test
    fun invalidationRejectsEveryPreviouslyCapturedToken() {
        val gate = LifecycleGate()
        val first = gate.capture()
        val second = gate.capture()

        assertTrue(first.isValid)
        assertTrue(second.isValid)

        gate.invalidate()

        assertFalse(first.isValid)
        assertFalse(second.isValid)
        assertFalse(gate.capture().isValid)
    }

    @Test
    fun invalidationIsIdempotent() {
        val gate = LifecycleGate()
        gate.invalidate()
        gate.invalidate()

        assertFalse(gate.capture().isValid)
    }
}
