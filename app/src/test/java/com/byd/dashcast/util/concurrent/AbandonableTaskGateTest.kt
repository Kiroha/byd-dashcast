package com.byd.dashcast.util.concurrent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbandonableTaskGateTest {

    @Test
    fun `running abandoned task blocks another admission until it exits`() {
        val gate = AbandonableTaskGate()
        val attempt = gate.tryAcquire()!!
        assertTrue(attempt.enter())

        attempt.cancel()
        attempt.releaseIfNotRunning()
        assertFalse(attempt.shouldContinue())
        assertNull(gate.tryAcquire())

        attempt.complete()
        assertNotNull(gate.tryAcquire())
    }

    @Test
    fun `cancelled queued task releases admission without running`() {
        val gate = AbandonableTaskGate()
        val attempt = gate.tryAcquire()!!

        attempt.cancel()
        attempt.releaseIfNotRunning()

        assertFalse(attempt.enter())
        assertNotNull(gate.tryAcquire())
    }
}