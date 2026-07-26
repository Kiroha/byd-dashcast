package com.byd.dashcast.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaRelaunchPolicyTest {
    @Test
    fun `fresh request relaunches`() {
        assertTrue(OtaRelaunchPolicy.shouldRelaunch(1_000L, 2_000L))
        assertTrue(OtaRelaunchPolicy.shouldRelaunch(
            1_000L,
            1_000L + OtaRelaunchPolicy.MAX_AGE_MS
        ))
    }

    @Test
    fun `missing stale or future request does not relaunch`() {
        assertFalse(OtaRelaunchPolicy.shouldRelaunch(0L, 2_000L))
        assertFalse(OtaRelaunchPolicy.shouldRelaunch(
            1_000L,
            1_001L + OtaRelaunchPolicy.MAX_AGE_MS
        ))
        assertFalse(OtaRelaunchPolicy.shouldRelaunch(2_000L, 1_000L))
    }
}