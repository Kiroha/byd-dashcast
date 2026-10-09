package com.byd.dashcast.hud

/** Process-local connection tracking and bounded retries, independent of navigation traffic. */
internal class NavigationListenerRecovery {
    private val lock = Any()
    private var owner: Any? = null
    private var connected = false
    private var eligible = false
    private var attempts = 0
    private var lastAttemptMs: Long? = null
    private var inFlight = false

    fun onCreated(token: Any) = synchronized(lock) {
        if (connected) resetRetries()
        owner = token
        connected = false
    }

    fun onConnected(token: Any): Boolean = synchronized(lock) {
        if (owner !== token) return false
        connected = true
        resetRetries()
        true
    }

    fun onDisconnected(token: Any): Boolean = synchronized(lock) {
        if (owner !== token) return false
        // A real loss of a confirmed connection gets a fresh retry budget. Repeated callbacks
        // from an already disconnected instance must not defeat the backoff.
        if (connected) resetRetries()
        connected = false
        true
    }

    fun onDestroyed(token: Any) = synchronized(lock) {
        if (owner !== token) return
        if (connected) resetRetries()
        connected = false
        owner = null
    }

    /** Returns true for an attempt, never as evidence that Android actually connected us. */
    fun maybeRecover(nowMs: Long, enabled: Boolean, accessGranted: Boolean,
                     requestRebind: () -> Unit): Boolean {
        synchronized(lock) {
            if (!enabled || !accessGranted) {
                eligible = false
                // A revoked grant invalidates a previous connection even if Android omitted
                // its disconnect callback. Disabling guidance alone leaves binding untouched.
                if (enabled && !accessGranted) connected = false
                resetRetries()
                return false
            }
            if (!eligible) {
                eligible = true
                resetRetries()
            }
            if (connected || inFlight) return false
            val last = lastAttemptMs
            if (last != null && nowMs - last < retryIntervalMs(attempts)) return false
            lastAttemptMs = nowMs
            attempts = (attempts + 1).coerceAtMost(5)
            inFlight = true
        }
        try {
            // Do not hold the state lock across Binder: lifecycle callbacks on Android's main
            // thread must remain free to confirm a connection while this request is in flight.
            requestRebind()
        } finally {
            synchronized(lock) { inFlight = false }
        }
        return true
    }

    fun snapshot(nowMs: Long): Snapshot = synchronized(lock) {
        Snapshot(connected, eligible, attempts, inFlight,
            lastAttemptMs?.let { (nowMs - it).coerceAtLeast(0L) })
    }

    private fun resetRetries() {
        attempts = 0
        lastAttemptMs = null
    }

    data class Snapshot(val connected: Boolean, val eligible: Boolean, val attempts: Int,
                        val inFlight: Boolean, val lastAttemptAgeMs: Long?)

    companion object {
        // No attempt ceiling: a slow OEM notification manager is retried throughout the session.
        private fun retryIntervalMs(attempts: Int): Long = when (attempts) {
            0, 1 -> 30_000L
            2 -> 60_000L
            3 -> 120_000L
            else -> 300_000L
        }
    }
}
