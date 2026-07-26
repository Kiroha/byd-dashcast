package com.byd.dashcast.util.concurrent

import java.util.concurrent.atomic.AtomicLong

/** Allows one callback-based operation at a time until its exact token completes. */
class AsyncOperationGate {
    private val nextToken = AtomicLong(1L)
    private val activeToken = AtomicLong(NO_TOKEN)

    fun tryStart(): Long? {
        val token = nextToken.getAndIncrement()
        return if (activeToken.compareAndSet(NO_TOKEN, token)) token else null
    }

    fun complete(token: Long): Boolean = activeToken.compareAndSet(token, NO_TOKEN)

    fun isInFlight(): Boolean = activeToken.get() != NO_TOKEN

    private companion object {
        const val NO_TOKEN = 0L
    }
}