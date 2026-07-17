package com.byd.dashcast.dilink5

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class D50fDashboardProbeTest {

    @Test
    fun sendsCorrectedHandshakeAndRestores() {
        val calls = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        val result = D50fDashboardProbe.run(
            { type, info ->
                calls += "$type/$info"
                when (calls.size) {
                    1 -> -1
                    2 -> 0
                    else -> 0
                }
            },
            { waits += it }
        )

        assertEquals(listOf("1000/18", "16/35", "1000/18"), calls)
        assertEquals(listOf(6_000L, 3_000L), waits)
        assertEquals(-1, result.resetCode)
        assertEquals(0, result.unlockCode)
        assertEquals(0, result.restoreCode)
    }

    @Test
    fun restoresWhenUnlockThrows() {
        val calls = mutableListOf<String>()

        try {
            D50fDashboardProbe.run(
                { type, info ->
                    calls += "$type/$info"
                    if (type == 16) error("unlock failed")
                    0
                },
                { }
            )
            fail("Expected unlock failure")
        } catch (_: IllegalStateException) {
            // Expected from the synthetic unlock failure.
        }

        assertEquals(listOf("1000/18", "16/35", "1000/18"), calls)
    }
}