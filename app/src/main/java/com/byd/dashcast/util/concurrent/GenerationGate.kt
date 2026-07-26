package com.byd.dashcast.util.concurrent

import java.util.concurrent.atomic.AtomicInteger

/** Restartable lifecycle gate: invalidation rejects old tokens without disabling future work. */
class GenerationGate {
    private val generation = AtomicInteger()

    fun capture(): Int = generation.get()

    fun invalidate() {
        generation.incrementAndGet()
    }

    fun isCurrent(token: Int): Boolean = generation.get() == token
}