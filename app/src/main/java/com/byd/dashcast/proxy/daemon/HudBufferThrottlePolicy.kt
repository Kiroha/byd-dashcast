package com.byd.dashcast.proxy.daemon

/**
 * How often a single feature id may write a buffer line into the HUD event log.
 *
 * [CanFeedbackListener] dedups on the payload hex, which works for an event and fails for a
 * stream. Feature `0x99000198` pushes at about 25 Hz and its six bytes are a signed value plus a
 * fast tick, so the hex differs on every single push and the dedup never fires. In
 * INC-20260826-194829 that one id took 903 of the 1000 buffer lines and left nine for everything
 * else; commit d56856d7 had fixed exactly this ("spams continuously and drowns the signal") and
 * 9f69377b's buffer branch undid it.
 *
 * An id allowlist was the obvious answer and is the wrong one: this recorder exists to find out
 * which ids carry the HUD semantics, so a list of the ids we already trust would blind it to the
 * discovery it is for. Rate-limiting keeps every stream in the log — at a sample a second, with
 * the count of what was skipped attached — and lets a rare event through untouched.
 *
 * A change in the integer value is never throttled. That is a state change, not a sample.
 */
object HudBufferThrottlePolicy {

    /** One buffer line per feature id per second. A 25 Hz stream costs ~36 lines a minute. */
    const val MIN_INTERVAL_MS = 1000L

    /**
     * @param intChanged the push also changed the feature's integer value.
     * @param lastRecordedAtMs when this id last wrote a buffer line, or 0 if it never has.
     * @param nowMs elapsed-realtime now.
     */
    @JvmStatic
    fun shouldRecord(intChanged: Boolean, lastRecordedAtMs: Long, nowMs: Long): Boolean {
        if (intChanged) return true
        if (lastRecordedAtMs <= 0L) return true
        // A clock that went backwards is not a reason to go quiet.
        val since = nowMs - lastRecordedAtMs
        return since < 0L || since >= MIN_INTERVAL_MS
    }

    /**
     * What to append to a recorded line so the reader sees the rate rather than a value that
     * looks like the only one that arrived.
     */
    @JvmStatic
    fun sinceSuffix(skipped: Int): String = if (skipped <= 0) "" else " +$skipped since"
}
