package com.byd.dashcast.ui.hotspot

/**
 * Pure cadence / verdict rules for [HotspotKeeper] — no Android dependency, unit-tested.
 *
 * Extracted because the retry policy is exactly what failed in the field: the previous
 * "5 attempts then a 5 min blackout" ceiling produced ~540 dispatches in 13.8 h AND repeatedly
 * happened to be blacked out at the moment the car was switched on (ACC-on), i.e. the one moment
 * the hotspot had to come up. Rules that decide *when* we act belong in a place where they can be
 * asserted without a device.
 */
internal object HotspotKeeperPolicy {

    /**
     * PROGRESSIVE, CAPPED retry ladder, indexed by the number of consecutive UNCONFIRMED ATTEMPTS
     * already made. Never gives up (the owner wants the hotspot on at all times), but stops
     * hammering when TetherFi is clearly refusing to start.
     *
     * An ATTEMPT is one CLAIMED launch sequence — stamped when the keeper commits to launching, NOT
     * when a route reports success. That distinction is the whole point of the ladder: bookkeeping
     * committed only on success left the ladder pinned at rung 0 on any unit where NO route ever
     * succeeds (TetherFi renamed the tile → every `am start` answers `Error: Activity class … does
     * not exist`; no daemon and no ADB → every route unavailable), so every 30 s probe ran a full
     * launch sequence: 120 attempts/h instead of the 12/h documented here.
     *
     * ATTEMPT arithmetic, measured from the first attempt of a failing streak (t in seconds):
     *   #1 t=0, #2 t=60, #3 t=120, #4 t=240, #5 t=360, #6 t=660, then every 300 s.
     *   → 15 attempts in the first hour, then a SUSTAINED 12 attempts/hour. Identical in BOTH
     *     regimes — "a route dispatched but the AP never came up" and "every route failed" — because
     *     the ladder is driven by attempts.
     *   OLD code (30 s cooldown × 5 attempts, then a 5 min blackout, repeating) = 5 dispatches per
     *   420 s ≈ 43/hour, matching the measured field figure of ~540 dispatches in 13.8 h (39/h).
     *   → ~3.3x calmer, and never permanently silent.
     *
     * SHELL arithmetic, sustained, worst case:
     *   NEW, a route dispatches but the AP stays down: 120 cadence probes/h (30 s) + 12 `am start`
     *        + 12 confirmation re-probes = 144 ops/h — and the re-probes refresh the probe clock, so
     *        they DISPLACE cadence probes rather than add to them (~132/h in practice). There is no
     *        separate launch-evidence probe: the before/after process snapshot rides on the two
     *        probes already being run (see [classifyLaunch]).
     *   NEW, every route fails: 120 cadence probes/h + at most 24 `am start` (daemon then ADB)
     *        = 144 ops/h; nothing is dispatched, so no confirmation re-probe is armed.
     *   OLD: 180 cadence probes/h (20 s) + 0 (the in-app startActivity is not a shell op) = 180/h.
     *   → sustained shell traffic is LOWER than the implementation this replaces, in both regimes.
     *   In the healthy case (hotspot UP) it is 120/h against 180/h.
     *
     * Reset to rung 0 on: a CONFIRMED launch, an observed UP state, or a boot/ACC-on/user pass.
     */
    val RETRY_LADDER_MS = longArrayOf(60_000L, 60_000L, 120_000L, 120_000L, 300_000L)

    /** A never-probed keeper (`lastProbeMs == 0`) is always due, so the first pass is immediate. */
    fun isProbeDue(nowMs: Long, lastProbeMs: Long, intervalMs: Long): Boolean =
        lastProbeMs <= 0L || nowMs - lastProbeMs >= intervalMs

    /**
     * Minimum spacing before the next attempt, given how many consecutive attempts have already
     * gone unconfirmed. Capped at the last rung of [RETRY_LADDER_MS] — there is no attempt ceiling
     * and no blackout, only a growing spacing.
     */
    fun retryIntervalMs(unconfirmedStreak: Int): Long {
        val rung = (unconfirmedStreak - 1).coerceIn(0, RETRY_LADDER_MS.size - 1)
        return RETRY_LADDER_MS[rung]
    }

    /** Unbounded: there is no attempt ceiling any more, only a minimum spacing. */
    fun isRestartDue(nowMs: Long, lastRestartMs: Long, intervalMs: Long): Boolean =
        lastRestartMs <= 0L || nowMs - lastRestartMs >= intervalMs

    /** Milliseconds before the next restart attempt is allowed (0 = allowed now). */
    fun msUntilNextRestart(nowMs: Long, lastRestartMs: Long, intervalMs: Long): Long {
        if (lastRestartMs <= 0L) return 0L
        val left = intervalMs - (nowMs - lastRestartMs)
        return if (left > 0L) left else 0L
    }

    /**
     * The dedupe rule, on ATTEMPTS: true when no launch sequence was claimed within the last
     * [dedupeMs].
     *
     * Same shape as [isRestartDue] but a DIFFERENT question, and that is the point — the retry
     * cadence is skipped by a boot/ACC-on pass, this one never is. `lastAttemptMs == 0` (a cold
     * process, or a process that has simply never had to restart TetherFi) is clear: the very
     * first launch must not be blocked.
     */
    fun isDedupeClear(nowMs: Long, lastAttemptMs: Long, dedupeMs: Long): Boolean =
        lastAttemptMs <= 0L || nowMs - lastAttemptMs >= dedupeMs

    /**
     * True only when `am start` EXPLICITLY reported a failure.
     *
     * Quiet or empty output is deliberately NOT a failure: falling back to the next route after a
     * launch that actually worked would fire a second ProxyTileActivity and bring back the exact
     * double popup this change removes (two draws 13.9 s apart, INC-20260721-184844). The +3 s /
     * +8 s re-probe is the authoritative verdict; this is only a fast explicit-refusal detector.
     *
     * `am start`'s success line is `Starting: Intent { ... }`, and "Warning: Activity not started,
     * its current task has been brought to the front" is a SUCCESS (the tile was already up), so
     * neither is matched here.
     */
    fun amStartFailed(output: String?): Boolean {
        if (output.isNullOrBlank()) return false
        return output.contains("Error:") ||
            output.contains("Error type") ||
            output.contains("Exception")
    }

    /**
     * Reads `key=value` out of the one-line-per-key probe payload (`pid=…`). Returns "" when the
     * key is absent or its value is empty — use [pidSnapshot] when those two must be told apart.
     */
    fun evidenceValue(output: String?, key: String): String {
        val text = output ?: return ""
        val at = text.indexOf(key)
        if (at < 0) return ""
        val from = at + key.length
        val end = text.indexOf('\n', from)
        return text.substring(from, if (end < 0) text.length else end).trim()
    }

    /** "We did not measure the process list", as opposed to "" = "there is no TetherFi process".
     *  A pid list is digits and spaces, so this can never collide with a real value. */
    const val PID_UNKNOWN = "?"

    /**
     * TetherFi's process id(s) as reported by a probe payload — [PID_UNKNOWN] when the payload
     * carries no `pid=` line at all (an older/partial output, or a probe that never ran), `""` when
     * the line is there and empty (no TetherFi process). Conflating those two is what would turn a
     * missing measurement into a confident "nothing ran".
     */
    fun pidSnapshot(output: String?): String {
        val text = output ?: return PID_UNKNOWN
        if (!text.contains("pid=")) return PID_UNKNOWN
        return evidenceValue(text, "pid=")
    }

    /** What a before/after process snapshot can say about ONE dispatch — see [classifyLaunch]. */
    enum class LaunchEvidence {
        /** No TetherFi process at dispatch time, one running 8 s later (or a different pid): this
         *  dispatch created it, so ProxyTileActivity DID run. */
        PROCESS_STARTED,

        /** No TetherFi process before AND none after: nothing was started. */
        NO_PROCESS,

        /** A TetherFi process was already alive BEFORE the dispatch: its existence afterwards
         *  proves nothing about this dispatch. */
        PROCESS_PREDATES,

        /** One of the two snapshots is missing: nothing can be concluded. */
        NO_SNAPSHOT
    }

    /**
     * Did THIS dispatch run the tile? — the question the AP probe cannot answer, decided by diffing
     * the process snapshot taken by the probe that PRECEDED the dispatch against the one taken by
     * the +8 s confirmation re-probe. Costs no extra shell round trip: both probes already run.
     *
     * What it can prove and what it cannot, deliberately:
     *  - process absent → present  ⇒ [PROCESS_STARTED]. The dispatch reached TetherFi; the tile ran
     *    and TetherFi declined to raise the AP. This is the verdict the field question needs.
     *  - absent → absent ⇒ [NO_PROCESS]. Nothing ran.
     *  - every pid replaced (none survived) ⇒ [PROCESS_STARTED]: TetherFi's process was recreated
     *    inside the window, which only a launch does.
     *  - any pid survived ⇒ [PROCESS_PREDATES], and NOTHING more: the previous implementation asserted
     *    "tile DID run" from a non-empty `pidof` alone, which is wrong for every dispatch after the
     *    first — Android keeps TetherFi's process cached for a long time, so dispatches #2…#N all
     *    "proved" a launch that may well have been suppressed. The tile is FLAG_ACTIVITY_NO_HISTORY,
     *    so it leaves no activity-manager record by +8 s either; on this ROM the question is simply
     *    not answerable in that state, and the log must say so.
     */
    fun classifyLaunch(pidBefore: String?, pidAfter: String?): LaunchEvidence {
        // `null` is "not measured", never "no process" — see [pidSnapshot].
        val before = pidBefore?.trim() ?: PID_UNKNOWN
        val after = pidAfter?.trim() ?: PID_UNKNOWN
        if (before == PID_UNKNOWN || after == PID_UNKNOWN) return LaunchEvidence.NO_SNAPSHOT
        if (before.isEmpty()) {
            return if (after.isEmpty()) LaunchEvidence.NO_PROCESS else LaunchEvidence.PROCESS_STARTED
        }
        // `pidof` prints a SPACE-SEPARATED LIST. A plain string inequality would score
        // "8123" → "8123 9200" (an unrelated TetherFi sub-process appearing inside the 8 s window,
        // main process untouched) as PROCESS_STARTED — a confident false positive of exactly the
        // class this function exists to remove. Only a snapshot in which NO pid survived is
        // evidence that this dispatch created the process; if any pid predates it, say so.
        val beforePids = before.split(' ').filter { it.isNotEmpty() }.toSet()
        val afterPids = after.split(' ').filter { it.isNotEmpty() }.toSet()
        if (afterPids.isEmpty()) return LaunchEvidence.PROCESS_PREDATES
        if (afterPids.none { it in beforePids }) return LaunchEvidence.PROCESS_STARTED
        return LaunchEvidence.PROCESS_PREDATES
    }
}
