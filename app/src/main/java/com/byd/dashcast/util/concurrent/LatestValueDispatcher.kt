package com.byd.dashcast.util.concurrent

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Serial latest-value dispatcher with at most one drain task and ordered terminal cleanup. */
class LatestValueDispatcher<T>(
    private val executor: ExecutorService,
    private val handler: ValueHandler<T>,
) {

    fun interface ValueHandler<T> {
        @Throws(Exception::class)
        fun accept(value: T)
    }

    private val latest = AtomicReference<T?>()
    private val drainScheduled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /** Publishes a value, replacing any not-yet-consumed value. Returns false after close/reject. */
    fun submit(value: T): Boolean {
        if (closed.get()) return false
        latest.set(value)
        if (closed.get()) {
            latest.set(null)
            return false
        }
        return scheduleDrain()
    }

    /** Cancels the pending value and serializes an action after any handler already in progress. */
    fun cancelPendingAndExecute(action: Runnable?): Boolean {
        if (action == null || closed.get()) return false
        latest.set(null)
        return try {
            executor.execute(action)
            true
        } catch (rejected: RejectedExecutionException) {
            false
        }
    }

    /** Drops pending values, runs terminal cleanup after any active handler, then shuts down. */
    fun close(terminalCleanup: Runnable?) {
        requireNotNull(terminalCleanup) { "terminalCleanup required" }
        if (!closed.compareAndSet(false, true)) return
        latest.set(null)
        try {
            executor.execute(terminalCleanup)
        } catch (rejected: RejectedExecutionException) {
            terminalCleanup.run()
        } finally {
            executor.shutdown()
        }
    }

    private fun scheduleDrain(): Boolean {
        if (!drainScheduled.compareAndSet(false, true)) return true
        return try {
            executor.execute { drain() }
            true
        } catch (rejected: RejectedExecutionException) {
            drainScheduled.set(false)
            latest.set(null)
            false
        }
    }

    private fun drain() {
        try {
            while (!closed.get()) {
                val value = latest.getAndSet(null) ?: return
                try {
                    handler.accept(value)
                } catch (ignored: Exception) {
                    // A failed value must not stall later values or terminal cleanup.
                }
            }
        } finally {
            drainScheduled.set(false)
            if (!closed.get() && latest.get() != null) scheduleDrain()
        }
    }
}
