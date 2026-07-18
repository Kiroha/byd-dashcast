package com.byd.dashcast.proxy.daemon;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Tracks the latest watchdog generation independently for each Layout package. */
final class FissionWatchdogRegistry {
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<String, Long> generations = new ConcurrentHashMap<>();

    long start(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            throw new IllegalArgumentException("packageName required");
        }
        long generation = sequence.incrementAndGet();
        generations.put(packageName, generation);
        return generation;
    }

    boolean isCurrent(String packageName, long generation) {
        Long current = generations.get(packageName);
        return current != null && current == generation;
    }

    void finish(String packageName, long generation) {
        generations.remove(packageName, generation);
    }

    boolean cancel(String packageName) {
        return packageName != null && generations.remove(packageName) != null;
    }

    int cancelAll() {
        int count = generations.size();
        generations.clear();
        return count;
    }

    int activePackages() {
        return generations.size();
    }
}