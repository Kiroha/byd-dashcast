package com.byd.dashcast.hud;

import java.util.concurrent.atomic.AtomicLong;

/** Correlates asynchronous HUD write acknowledgements with the latest notification content. */
final class HudDeliveryTracker {
    private final AtomicLong currentGeneration = new AtomicLong();
    private final AtomicLong deliveredGeneration = new AtomicLong(Long.MIN_VALUE);

    long beginContent() {
        return currentGeneration.incrementAndGet();
    }

    void markDelivered(long generation) {
        deliveredGeneration.accumulateAndGet(generation, Math::max);
    }

    boolean currentContentWasDelivered() {
        return deliveredGeneration.get() == currentGeneration.get();
    }

    void invalidate() {
        currentGeneration.incrementAndGet();
    }
}