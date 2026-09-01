package com.byd.dashcast.fission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationAttemptGateTest {

    @Test
    fun `stale completion cannot release reclaimed activation`() {
        val gate = ActivationAttemptGate(60_000)
        val first = gate.tryAcquire(1_000)!!
        val second = gate.tryAcquire(61_001)!!

        assertTrue(second.reclaimed)
        assertFalse(gate.release(first.token))
        assertNull(gate.tryAcquire(61_002))

        assertTrue(gate.release(second.token))
        assertNotNull(gate.tryAcquire(61_003))
    }

    @Test
    fun `force acquisition invalidates prior owner`() {
        val gate = ActivationAttemptGate(60_000)
        val first = gate.tryAcquire(0)!!
        val purge = gate.forceAcquire(10)

        assertFalse(gate.release(first.token))
        assertTrue(gate.release(purge.token))
    }
}