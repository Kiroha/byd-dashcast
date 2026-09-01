package com.byd.dashcast.cluster

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** Serializes complete eviction workflows, including the caller's asynchronous restoration. */
internal class EvictionOperationQueue {
    fun interface Operation {
        fun start(lease: Lease)
    }

    class Lease internal constructor(private val release: Runnable) {
        private val physicalDone = AtomicBoolean(false)
        private val callerDone = AtomicBoolean(false)
        private val released = AtomicBoolean(false)

        fun markPhysicalDone() {
            physicalDone.set(true)
            releaseIfComplete()
        }

        fun markCallerDone() {
            callerDone.set(true)
            releaseIfComplete()
        }

        private fun releaseIfComplete() {
            if (physicalDone.get() && callerDone.get() &&
                released.compareAndSet(false, true)) {
                release.run()
            }
        }
    }

    private val pending = ArrayDeque<Operation>()
    private var running = false

    fun submit(operation: Operation) {
        val startNow = synchronized(this) {
            if (running) {
                pending.addLast(operation)
                false
            } else {
                running = true
                true
            }
        }
        if (startNow) start(operation)
    }

    private fun start(operation: Operation) {
        val lease = Lease(Runnable { finish() })
        try {
            operation.start(lease)
        } catch (error: Throwable) {
            lease.markPhysicalDone()
            lease.markCallerDone()
            throw error
        }
    }

    private fun finish() {
        val next = synchronized(this) {
            if (pending.isEmpty()) {
                running = false
                null
            } else {
                pending.removeFirst()
            }
        }
        if (next != null) start(next)
    }
}