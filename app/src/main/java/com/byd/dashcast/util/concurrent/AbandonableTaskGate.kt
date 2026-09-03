package com.byd.dashcast.util.concurrent

import java.util.concurrent.atomic.AtomicReference

/** Allows one task at a time and keeps admission closed while an abandoned task is still running. */
class AbandonableTaskGate {
    private val active = AtomicReference<Attempt?>()

    fun tryAcquire(): Attempt? {
        val attempt = Attempt(this)
        return if (active.compareAndSet(null, attempt)) attempt else null
    }

    private fun release(attempt: Attempt) {
        active.compareAndSet(attempt, null)
    }

    class Attempt internal constructor(private val gate: AbandonableTaskGate) {
        @Volatile private var cancelled = false
        private var started = false
        private var finished = false

        @Synchronized
        fun enter(): Boolean {
            if (cancelled || finished) return false
            started = true
            return true
        }

        @Synchronized
        fun cancel() {
            cancelled = true
        }

        fun shouldContinue(): Boolean = !cancelled && !Thread.currentThread().isInterrupted

        fun releaseIfNotRunning() {
            val release = synchronized(this) {
                if (started && !finished) {
                    false
                } else {
                    finished = true
                    true
                }
            }
            if (release) gate.release(this)
        }

        fun complete() {
            synchronized(this) { finished = true }
            gate.release(this)
        }
    }
}