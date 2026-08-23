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
        assertFalse(ClusterShotSchedulePolicy.shouldAppPrune(2, 60_000, 0, 45_000, 30_000, 30_000, true))
    }

    @Test
    fun `failed active capture keeps app prune fallback`() {
        assertTrue(ClusterShotSchedulePolicy.shouldAppPrune(2, 60_000, 0, 0, 30_000, 30_000, true))
    }

    @Test
    fun `app keeps pruning after projection stops`() {
        assertTrue(ClusterShotSchedulePolicy.shouldAppPrune(-1, 60_000, 0, 59_000, 30_000, 30_000, true))
        assertFalse(ClusterShotSchedulePolicy.shouldAppPrune(-1, 60_000, 45_000, 0, 30_000, 30_000, true))
    }

    /**
     * AUD-PERF-P2 — a process that has never captured must never prune. This is the case that
     * previously fired every 30 s forever on a car that never projects, each tick costing a full
     * ADB TCP + RSA handshake to clean a directory that was never created.
     */
    @Test
    fun `never having captured suppresses the prune entirely`() {
        // Idle (no projection) — the branch that used to return true unconditionally.
        assertFalse(ClusterShotSchedulePolicy.shouldAppPrune(-1, 60_000, 0, 0, 30_000, 30_000, false))
        // Still suppressed however long the car has been idle.
        assertFalse(ClusterShotSchedulePolicy.shouldAppPrune(-1, 9_999_000, 0, 0, 30_000, 30_000, false))
        // Suppressed while projecting too, until the first capture stamps sLastCaptureMs.
        assertFalse(ClusterShotSchedulePolicy.shouldAppPrune(2, 60_000, 0, 0, 30_000, 30_000, false))
    }

    /** Once a capture has happened the prior behaviour is preserved exactly. */
    @Test
    fun `after a first capture the prune behaves as before`() {
        assertTrue(ClusterShotSchedulePolicy.shouldAppPrune(-1, 60_000, 0, 0, 30_000, 30_000, true))
        assertTrue(ClusterShotSchedulePolicy.shouldAppPrune(2, 60_000, 0, 0, 30_000, 30_000, true))
        // The interval gate still wins over everCaptured.
        assertFalse(ClusterShotSchedulePolicy.shouldAppPrune(-1, 60_000, 45_000, 0, 30_000, 30_000, true))
    }

    /**
     * REGRESSION (shipped in 1.8.39-beta): the capture-cadence ramp stretched captures to 90 s
     * while the daemon-staleness bound stayed at 30 s, so the app-side prune — an ADB TCP + RSA
     * handshake each time — fired twice per cycle DURING projection, where before it never fired
     * at all. daemonStaleMs must track the capture cadence.
     */
    @Test
    fun `projecting on the ramped cadence never app-prunes between captures`() {
        val capture = 90_000L
        val stale = capture + 30_000L          // what the recorder now passes
        // 30s and 60s after a capture: the old fixed 30s bound fired here. It must not.
        assertFalse(ClusterShotSchedulePolicy.shouldAppPrune(2, 30_000, 0, 0, 30_000, stale, true))
        assertFalse(ClusterShotSchedulePolicy.shouldAppPrune(2, 60_000, 0, 0, 30_000, stale, true))
        // Captures genuinely failing — stamp older than a full cycle plus margin: prune again.
        assertTrue(ClusterShotSchedulePolicy.shouldAppPrune(2, 130_000, 0, 0, 30_000, stale, true))
    }

    /** Idle behaviour must be untouched by that fix: the clusterId<=0 arm short-circuits. */
    @Test
    fun `idle prune cadence is unaffected by the daemon-staleness bound`() {
        assertTrue(ClusterShotSchedulePolicy.shouldAppPrune(-1, 30_000, 0, 0, 30_000, 120_000, true))
        assertFalse(ClusterShotSchedulePolicy.shouldAppPrune(-1, 20_000, 0, 0, 30_000, 120_000, true))
        // and still nothing at all before a first capture
        assertFalse(ClusterShotSchedulePolicy.shouldAppPrune(-1, 30_000, 0, 0, 30_000, 120_000, false))
    }

    /**
     * The ramp arm (15 s capture cadence -> 45 s staleness bound) was left unpinned by the first
     * regression test, which only covered the 90 s steady arm. Captures ride the 10 s keeper
     * heartbeat, so a 15 s threshold actually fires every ~20 s.
     */
    @Test
    fun `ramp cadence never app-prunes while captures are healthy`() {
        val stale = 15_000L + 30_000L      // what the recorder passes during the ramp
        // Healthy: the daemon stamp is refreshed every ~20s, so it never reaches 45s.
        assertFalse(ClusterShotSchedulePolicy.shouldAppPrune(2, 20_000, 0, 0, 30_000, stale, true))
        assertFalse(ClusterShotSchedulePolicy.shouldAppPrune(2, 40_000, 0, 0, 30_000, stale, true))
        // Captures genuinely failing: the fallback must still engage past the bound.
        assertTrue(ClusterShotSchedulePolicy.shouldAppPrune(2, 50_000, 0, 0, 30_000, stale, true))
    }
}
