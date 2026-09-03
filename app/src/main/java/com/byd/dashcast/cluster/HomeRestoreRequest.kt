package com.byd.dashcast.cluster

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether an eviction has to put the launcher back in front when it is done.
 *
 * Its own object because the three rules that matter are easy to get wrong and were untestable
 * while this was a bare flag inside the tracker: it must be armed ONLY by
 * [EvictionOutcomePolicy.Outcome.KEEP_AND_RESTORE_HOME], it must be consumable exactly once, and
 * it must not survive into a later eviction. A stale request would launch the home screen out of
 * nowhere on some unrelated Stop press.
 *
 * Set from the force-stop callback thread and read on the main thread, hence the atomic.
 */
class HomeRestoreRequest {

    private val armed = AtomicBoolean(false)

    /** Called at the start of every eviction. One eviction never inherits another's request. */
    fun reset() {
        armed.set(false)
    }

    /** Arms only for the one outcome that means "an app we could not kill is on display 0". */
    fun arm(outcome: EvictionOutcomePolicy.Outcome) {
        if (outcome == EvictionOutcomePolicy.Outcome.KEEP_AND_RESTORE_HOME) armed.set(true)
    }

    /**
     * Reads and clears. Both teardown call sites ask, on success and on failure alike, so this
     * must answer true to the first of them and false to the second.
     */
    fun consume(): Boolean = armed.getAndSet(false)
}
