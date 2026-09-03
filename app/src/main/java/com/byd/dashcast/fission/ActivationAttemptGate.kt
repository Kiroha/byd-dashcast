package com.byd.dashcast.fission

/** Single-owner gate whose expired attempt cannot release a newer reclaimed owner. */
class ActivationAttemptGate(private val maxAgeMs: Long) {
    data class Acquisition(val token: Long, val reclaimed: Boolean)

    private var nextToken = 0L
    private var activeToken = 0L
    private var startedAtMs = 0L

    @Synchronized
    fun tryAcquire(nowMs: Long): Acquisition? {
        val held = activeToken != 0L
        if (held && nowMs - startedAtMs < maxAgeMs) return null
        val token = ++nextToken
        activeToken = token
        startedAtMs = nowMs
        return Acquisition(token, reclaimed = held)
    }

    @Synchronized
    fun forceAcquire(nowMs: Long): Acquisition {
        val token = ++nextToken
        val reclaimed = activeToken != 0L
        activeToken = token
        startedAtMs = nowMs
        return Acquisition(token, reclaimed)
    }

    @Synchronized
    fun release(token: Long): Boolean {
        if (token == 0L || activeToken != token) return false
        activeToken = 0L
        startedAtMs = 0L
        return true
    }

    @Synchronized
    fun clear() {
        activeToken = 0L
        startedAtMs = 0L
    }

    @Synchronized
    fun heldMs(nowMs: Long): Long =
        if (activeToken == 0L) 0L else (nowMs - startedAtMs).coerceAtLeast(0L)
}