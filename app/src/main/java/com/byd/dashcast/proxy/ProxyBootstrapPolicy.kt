package com.byd.dashcast.proxy

/** Pure timing policy for the Binder wait that follows a proxy bootstrap attempt. */
object ProxyBootstrapPolicy {

    /**
     * How long the app waits for the daemon's binder rebroadcast after touching the trigger file,
     * when the daemon is already alive (the REBROADCAST fast path).
     *
     * This number and [TRIGGER_SLOW_POLL_MS] are ONE decision split across two processes, and they
     * used to be two unrelated literals joined only by a prose comment. That cost a shipped
     * regression: the daemon's poll was raised from 1 s to 10 s while this budget stayed at 5 s, so
     * the rebroadcast recovery timed out deterministically and fell through to a full
     * kill-and-respawn bootstrap -- in exactly the DL5 inotify-failure case the poll exists to
     * cover. The
     * comments meant to prevent that had themselves gone stale and misstated both numbers.
     *
     * They are now derived from each other, so the invariant cannot be broken by editing one side.
     */
    const val REBROADCAST_BUDGET_MS = 5_000L

    /** Headroom for broadcast delivery + receiver dispatch on a slow DiLink SoC. */
    const val TRIGGER_DELIVERY_MARGIN_MS = 2_000L

    /**
     * Steady-state period of the daemon's trigger-file poll (`ProxyDaemonMain.installSelfHeal-
     * Heartbeat`). Derived, never written by hand: it is structurally guaranteed to leave
     * [TRIGGER_DELIVERY_MARGIN_MS] of slack inside [REBROADCAST_BUDGET_MS].
     */
    const val TRIGGER_SLOW_POLL_MS = REBROADCAST_BUDGET_MS - TRIGGER_DELIVERY_MARGIN_MS
    @JvmStatic
    fun binderWaitMs(bootstrapResult: String?, transportUnreachable: Boolean,
                     rebroadcastMs: Long, transportGraceMs: Long, coldSpawnMs: Long): Long {
        val result = bootstrapResult?.trim() ?: ""
        if (transportUnreachable) return transportGraceMs
        if (result.startsWith("REBROADCAST")) return rebroadcastMs
        return coldSpawnMs
    }
}
