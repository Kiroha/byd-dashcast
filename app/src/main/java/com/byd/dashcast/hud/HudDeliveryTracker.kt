package com.byd.dashcast.hud

import java.util.concurrent.atomic.AtomicLong

/** Correlates asynchronous HUD write acknowledgements with the latest notification content. */
internal class HudDeliveryTracker {
    private val currentGeneration = AtomicLong()
    private val deliveredGeneration = AtomicLong(Long.MIN_VALUE)

    fun beginContent(): Long {
        return currentGeneration.incrementAndGet()
    }

    fun markDelivered(generation: Long) {
        deliveredGeneration.accumulateAndGet(generation) { left, right -> Math.max(left, right) }
    }

    fun currentContentWasDelivered(): Boolean {
        return deliveredGeneration.get() == currentGeneration.get()
    }

    fun invalidate() {
        currentGeneration.incrementAndGet()
    }
}
