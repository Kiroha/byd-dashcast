package com.byd.dashcast.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeActionGateTest {

    @Test
    fun `only one action is admitted and completion is delivered once`() {
        val results = mutableListOf<Boolean>()
        val gate = ImeActionGate()
        val first = gate.begin { results += it }!!

        assertNull(gate.begin { results += it })
        assertTrue(gate.isCurrent(first))
        gate.finish(first, true)
        gate.finish(first, false)

        assertEquals(listOf(true), results)
    }

    @Test
    fun `teardown rejects current action and makes stale success inert`() {
        val results = mutableListOf<Boolean>()
        val gate = ImeActionGate()
        val operation = gate.begin { results += it }!!

        gate.cancelCurrent()
        assertFalse(gate.isCurrent(operation))
        gate.finish(operation, true)

        assertEquals(listOf(false), results)
        assertTrue(gate.begin { } != null)
    }
}