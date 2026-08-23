package com.byd.dashcast.report

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterShotSchedulePolicyTest {
    @Test
    fun `captures only an active due projection`() {
        assertTrue(ClusterShotSchedulePolicy.shouldCapture(2, 20_000, 0, 15_000))
        assertFalse(ClusterShotSchedulePolicy.shouldCapture(-1, 20_000, 0, 15_000))
        assertFalse(ClusterShotSchedulePolicy.shouldCapture(2, 20_000, 10_000, 15_000))
    }

    @Test
    fun `recent daemon prune suppresses active app prune`() {
        assertFalse(ClusterShotSchedulePolicy.shouldAppPrune(2, 60_000, 0, 45_000, 30_000, true))
    }

    @Test
    fun `failed active capture keeps app prune fallback`() {
        assertTrue(ClusterShotSchedulePolicy.shouldAppPrune(2, 60_000, 0, 0, 30_000, true))
    }

    @Test
    fun `app keeps pruning after projection stops`() {
        assertTrue(ClusterShotSchedulePolicy.shouldAppPrune(-1, 60_000, 0, 59_000, 30_000, true))
        assertFalse(ClusterShotSchedulePolicy.shouldAppPrune(-1, 60_000, 45_000, 0, 30_000, true))
    }

    /**
     * AUD-PERF-P2 — a process that has never captured must never prune. This is the case that
     * previously fired every 30 s forever on a car that never projects, each tick costing a full
     * ADB TCP + RSA handshake to clean a directory that was never created.
     */
    @Test
    fun `never having captured suppresses the prune entirely`() {
        // Idle (no projection) — the branch that used to return true unconditionally.
        assertFalse(ClusterShotSchedulePolicy.shouldAppPrune(-1, 60_000, 0, 0, 30_000, false))
        // Still suppressed however long the car has been idle.
        assertFalse(ClusterShotSchedulePolicy.shouldAppPrune(-1, 9_999_000, 0, 0, 30_000, false))
        // Suppressed while projecting too, until the first capture stamps sLastCaptureMs.
        assertFalse(ClusterShotSchedulePolicy.shouldAppPrune(2, 60_000, 0, 0, 30_000, false))
    }

    /** Once a capture has happened the prior behaviour is preserved exactly. */
    @Test
    fun `after a first capture the prune behaves as before`() {
        assertTrue(ClusterShotSchedulePolicy.shouldAppPrune(-1, 60_000, 0, 0, 30_000, true))
        assertTrue(ClusterShotSchedulePolicy.shouldAppPrune(2, 60_000, 0, 0, 30_000, true))
        // The interval gate still wins over everCaptured.
        assertFalse(ClusterShotSchedulePolicy.shouldAppPrune(-1, 60_000, 45_000, 0, 30_000, true))
    }
}
