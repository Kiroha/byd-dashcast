package com.byd.dashcast.ui.hotspot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotKeeperPolicyTest {

    @Test
    fun `first pass is due immediately`() {
        assertTrue(HotspotKeeperPolicy.isProbeDue(1_000, 0, 30_000))
        assertTrue(HotspotKeeperPolicy.isRestartDue(1_000, 0, 60_000))
    }

    @Test
    fun `probe respects the cadence`() {
        assertFalse(HotspotKeeperPolicy.isProbeDue(50_000, 30_000, 30_000))
        assertTrue(HotspotKeeperPolicy.isProbeDue(60_000, 30_000, 30_000))
    }

    @Test
    fun `restart never gives up but never bursts`() {
        assertFalse(HotspotKeeperPolicy.isRestartDue(90_000, 60_000, 60_000))
        assertTrue(HotspotKeeperPolicy.isRestartDue(120_000, 60_000, 60_000))
        // No attempt ceiling exists any more: the same rules apply at attempt 1 and at 500.
        assertTrue(HotspotKeeperPolicy.isRestartDue(3_600_000, 3_540_000, 60_000))
    }

    @Test
    fun `retry ladder grows and then caps`() {
        // Rungs, indexed by the number of consecutive unconfirmed attempts already made.
        assertEquals(60_000L, HotspotKeeperPolicy.retryIntervalMs(1))
        assertEquals(60_000L, HotspotKeeperPolicy.retryIntervalMs(2))
        assertEquals(120_000L, HotspotKeeperPolicy.retryIntervalMs(3))
        assertEquals(120_000L, HotspotKeeperPolicy.retryIntervalMs(4))
        assertEquals(300_000L, HotspotKeeperPolicy.retryIntervalMs(5))
        // Capped: a hotspot that has refused to start for hours is still retried every 5 min.
        assertEquals(300_000L, HotspotKeeperPolicy.retryIntervalMs(500))
    }

    @Test
    fun `a reset streak retries fast again`() {
        // A confirmed launch / an UP probe / a boot-ACC-on pass sets the streak back to 0, which
        // must put the next retry back on the FIRST rung, not on the capped one.
        assertEquals(60_000L, HotspotKeeperPolicy.retryIntervalMs(0))
        assertEquals(60_000L, HotspotKeeperPolicy.retryIntervalMs(-3))
    }

    @Test
    fun `sustained dispatch rate is lower than the old policy`() {
        // Replays the ladder over one hour of continuous failure and asserts the result is calmer
        // than the measured OLD behaviour (~39-43 dispatches/h).
        var now = 0L
        var streak = 0
        var dispatches = 0
        while (now <= 3_600_000L) {
            dispatches++
            streak++
            now += HotspotKeeperPolicy.retryIntervalMs(streak)
        }
        assertEquals(15, dispatches)
        // Steady state, once the ladder is capped: 3600 / 300 = 12 dispatches per hour.
        assertEquals(300_000L, HotspotKeeperPolicy.retryIntervalMs(streak))
    }

    @Test
    fun `the ladder engages even when no route ever succeeds`() {
        // The keeper drives this ladder from ATTEMPTS. Bookkeeping committed only when a route
        // reported SUCCESS left the streak at 0 and the attempt clock at 0 for the whole process
        // lifetime on a unit where every route fails (TetherFi renamed the tile → every `am start`
        // answers "Error: Activity class … does not exist"; or no daemon and no ADB), so every
        // 30 s probe that read DOWN ran a full launch sequence: 120 attempts/h.
        //
        // Replayed exactly as the keeper runs it: a probe every PROBE_INTERVAL_MS, an attempt
        // whenever the cadence allows one, and the streak climbing on every ATTEMPT.
        var now = 30_000L
        var lastAttemptMs = 0L
        var streak = 0
        var attempts = 0
        while (now <= 3_600_000L) {
            if (HotspotKeeperPolicy.isRestartDue(
                    now, lastAttemptMs, HotspotKeeperPolicy.retryIntervalMs(streak))) {
                attempts++
                streak++
                lastAttemptMs = now
            }
            now += 30_000L
        }
        // The same 15/h the succeeding case gets — not the 120/h (one per probe) of a pinned ladder.
        assertEquals(15, attempts)
        assertEquals(300_000L, HotspotKeeperPolicy.retryIntervalMs(streak))
        // And the page's countdown is a real one, instead of a permanent "due now".
        assertTrue(HotspotKeeperPolicy.msUntilNextRestart(
            lastAttemptMs + 1_000L, lastAttemptMs, HotspotKeeperPolicy.retryIntervalMs(streak)) > 0L)
    }

    @Test
    fun `countdown to the next attempt is clamped`() {
        assertEquals(40_000L, HotspotKeeperPolicy.msUntilNextRestart(80_000, 60_000, 60_000))
        assertEquals(0L, HotspotKeeperPolicy.msUntilNextRestart(200_000, 60_000, 60_000))
        assertEquals(0L, HotspotKeeperPolicy.msUntilNextRestart(200_000, 0, 60_000))
    }

    @Test
    fun `dedupe lets the very first launch through`() {
        // Timeline (b) of the double-popup bug: an already-running process at ACC-on has never
        // restarted TetherFi, so lastAttemptMs is still 0. An arm-based dedupe could not see that;
        // a dispatch-based one must not block the first genuine launch either.
        assertTrue(HotspotKeeperPolicy.isDedupeClear(1_000, 0, 20_000))
    }

    @Test
    fun `dedupe collapses the cold-process double dispatch`() {
        // Timeline (a): the heartbeat's first pass dispatches at t=1500, BootReceiver's pass asks
        // at t=3300. 1.8 s apart → the second one is refused, so the user sees ONE popup.
        assertFalse(HotspotKeeperPolicy.isDedupeClear(3_300, 1_500, 20_000))
        // The observed field gap was 13.9 s (INC-20260721-184844) — still inside the window.
        assertFalse(HotspotKeeperPolicy.isDedupeClear(15_400, 1_500, 20_000))
    }

    @Test
    fun `dedupe never suppresses a legitimate retry`() {
        // The window (20 s) is well under the first retry rung (60 s), so by the time the cadence
        // allows another attempt the dedupe is always clear.
        assertTrue(HotspotKeeperPolicy.isDedupeClear(21_500, 1_500, 20_000))
        assertTrue(HotspotKeeperPolicy.isDedupeClear(61_500, 1_500, 20_000))
        assertTrue(20_000L < HotspotKeeperPolicy.RETRY_LADDER_MS[0])
    }

    @Test
    fun `quiet am output is not a failure`() {
        assertFalse(HotspotKeeperPolicy.amStartFailed(null))
        assertFalse(HotspotKeeperPolicy.amStartFailed(""))
        assertFalse(HotspotKeeperPolicy.amStartFailed(
            "Starting: Intent { flg=0x50000000 cmp=com.pyamsoft.tetherfi/.tile.ProxyTileActivity }"))
        // Already in front = the tile is up, i.e. success.
        assertFalse(HotspotKeeperPolicy.amStartFailed(
            "Warning: Activity not started, its current task has been brought to the front"))
    }

    @Test
    fun `explicit am refusals switch to the next route`() {
        assertTrue(HotspotKeeperPolicy.amStartFailed(
            "Error: Activity class {com.pyamsoft.tetherfi/...} does not exist."))
        assertTrue(HotspotKeeperPolicy.amStartFailed("Error type 3"))
        assertTrue(HotspotKeeperPolicy.amStartFailed(
            "java.lang.SecurityException: Permission Denial"))
    }

    @Test
    fun `launch evidence is parsed out of the probe payload`() {
        val out = "DOWN\npid=8123 8140\n"
        assertEquals("8123 8140", HotspotKeeperPolicy.evidenceValue(out, "pid="))
        assertEquals("", HotspotKeeperPolicy.evidenceValue("DOWN\npid=\n", "pid="))
        assertEquals("", HotspotKeeperPolicy.evidenceValue(null, "pid="))
        assertEquals("", HotspotKeeperPolicy.evidenceValue("nothing useful", "pid="))
    }

    @Test
    fun `a missing pid line is not the same as no process`() {
        // "the line is there and empty" = no TetherFi process; "no line at all" = we never
        // measured. Conflating them would turn a missing measurement into a confident "nothing ran".
        assertEquals("8123", HotspotKeeperPolicy.pidSnapshot("UP\npid=8123\n"))
        assertEquals("", HotspotKeeperPolicy.pidSnapshot("DOWN\npid=\n"))
        assertEquals(HotspotKeeperPolicy.PID_UNKNOWN, HotspotKeeperPolicy.pidSnapshot("DOWN\n"))
        assertEquals(HotspotKeeperPolicy.PID_UNKNOWN, HotspotKeeperPolicy.pidSnapshot(null))
    }

    @Test
    fun `launch evidence proves a tile run only when the process appeared`() {
        // Absent before, present after: this dispatch created TetherFi's process, so the tile ran.
        assertEquals(HotspotKeeperPolicy.LaunchEvidence.PROCESS_STARTED,
            HotspotKeeperPolicy.classifyLaunch("", "8123"))
        // A different pid = the process was replaced after the dispatch: something started it.
        assertEquals(HotspotKeeperPolicy.LaunchEvidence.PROCESS_STARTED,
            HotspotKeeperPolicy.classifyLaunch("8123", "9001"))
        // Nothing before, nothing after: nothing ran.
        assertEquals(HotspotKeeperPolicy.LaunchEvidence.NO_PROCESS,
            HotspotKeeperPolicy.classifyLaunch("", ""))
    }

    @Test
    fun `a process that predates the dispatch proves nothing`() {
        // THE regression this guards: the old rule said "pidof non-empty ⇒ the tile DID run", so
        // once TetherFi's process had started once (Android caches it for a long time) every later
        // dispatch logged a confident "tile DID run" — wrong for dispatches #2…#N, and it would
        // have exonerated the launch path on a car where the launch was in fact being suppressed.
        assertEquals(HotspotKeeperPolicy.LaunchEvidence.PROCESS_PREDATES,
            HotspotKeeperPolicy.classifyLaunch("8123", "8123"))
        // Alive before, gone after: still says nothing about whether the tile ran.
        assertEquals(HotspotKeeperPolicy.LaunchEvidence.PROCESS_PREDATES,
            HotspotKeeperPolicy.classifyLaunch("8123", ""))
    }

    @Test
    fun `launch evidence refuses to guess without both snapshots`() {
        // Blind in-app launch (the probe could not run), or a payload with no pid line.
        assertEquals(HotspotKeeperPolicy.LaunchEvidence.NO_SNAPSHOT,
            HotspotKeeperPolicy.classifyLaunch(HotspotKeeperPolicy.PID_UNKNOWN, "8123"))
        assertEquals(HotspotKeeperPolicy.LaunchEvidence.NO_SNAPSHOT,
            HotspotKeeperPolicy.classifyLaunch("", HotspotKeeperPolicy.PID_UNKNOWN))
        assertEquals(HotspotKeeperPolicy.LaunchEvidence.NO_SNAPSHOT,
            HotspotKeeperPolicy.classifyLaunch(null, null))
    }
}
