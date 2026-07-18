package com.byd.dashcast.infrastructure

import org.junit.Assert.assertEquals
import org.junit.Test

class AdbTimeoutPolicyTest {
    @Test
    fun `first command keeps authorization window`() {
        assertEquals(15_000, AdbTimeoutPolicy.effectiveIdleTimeoutMs(6_000, false, 15_000))
    }

    @Test
    fun `proven transport uses operation-specific budget`() {
        assertEquals(6_000, AdbTimeoutPolicy.effectiveIdleTimeoutMs(6_000, true, 15_000))
        assertEquals(120_000, AdbTimeoutPolicy.effectiveIdleTimeoutMs(120_000, false, 15_000))
    }

    @Test
    fun `bootstrap retains the historical caller budget`() {
        assertEquals(
            8_000,
            AdbTimeoutPolicy.effectiveIdleTimeoutMs(
                AdbLocalClient.BOOTSTRAP_IDLE_TIMEOUT_MS,
                true,
                AdbLocalClient.FIRST_OPERATION_IDLE_TIMEOUT_MS
            )
        )
    }
}