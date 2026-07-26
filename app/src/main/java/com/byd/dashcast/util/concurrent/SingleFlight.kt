package com.byd.dashcast.util.concurrent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Coalesces concurrent callers onto one leader operation and one immutable result. */
class SingleFlight<T> {

    private var current: Attempt<T>? = null

    @Synchronized
    fun join(): Ticket<T> {
        val existing = current
        if (existing == null) {
            val attempt = Attempt<T>()
            current = attempt
            return Ticket(this, attempt, true)
        }
        return Ticket(this, existing, false)
    }

    @Synchronized
    private fun clear(attempt: Attempt<T>) {
        if (current === attempt) current = null
    }

    internal class Attempt<T> {
        private val completed = CountDownLatch(1)
        private val completionClaimed = AtomicBoolean(false)
        @Volatile private var result: T? = null

        fun complete(value: T) {
            if (!completionClaimed.compareAndSet(false, true)) {
                throw IllegalStateException("single-flight attempt already completed")
            }
            result = value
            completed.countDown()
        }

        @Throws(InterruptedException::class, TimeoutException::class)
        fun await(timeout: Long, unit: TimeUnit): T {
            if (!completed.await(timeout, unit)) {
                throw TimeoutException("single-flight result timed out")
            }
            @Suppress("UNCHECKED_CAST")
            return result as T
        }
    }

    class Ticket<T> internal constructor(
        private val owner: SingleFlight<T>,
        private val attempt: Attempt<T>,
        private val leader: Boolean,
    ) {
        val isLeader: Boolean get() = leader

        fun complete(result: T) {
            if (!leader) throw IllegalStateException("only the leader can complete an attempt")
            attempt.complete(result)
            owner.clear(attempt)
        }

        @Throws(InterruptedException::class, TimeoutException::class)
        fun await(timeout: Long, unit: TimeUnit): T {
            return attempt.await(timeout, unit)
        }
    }
}
