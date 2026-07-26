package com.byd.dashcast.util.concurrent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncOperationGateTest {
    @Test
    fun `rejects overlap until exact operation completes`() {
        val gate = AsyncOperationGate()
        val first = gate.tryStart()

        assertNotNull(first)
        assertTrue(gate.isInFlight())
        assertNull(gate.tryStart())
        assertTrue(gate.complete(first!!))
        assertFalse(gate.isInFlight())
        assertNotNull(gate.tryStart())
    }

    @Test
    fun `stale completion cannot release newer operation`() {
        val gate = AsyncOperationGate()
        val first = gate.tryStart()!!
        assertTrue(gate.complete(first))
        val second = gate.tryStart()!!

        assertFalse(gate.complete(first))
        assertTrue(gate.isInFlight())
        assertTrue(gate.complete(second))
    }
}