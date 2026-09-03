package com.byd.dashcast.proxy.daemon

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Tracks the latest watchdog generation independently for each Layout package. */
internal class FissionWatchdogRegistry {
    private val sequence = AtomicLong()
    private val generations = ConcurrentHashMap<String, Long>()

    fun start(packageName: String?): Long {
        require(!packageName.isNullOrEmpty()) { "packageName required" }
        val generation = sequence.incrementAndGet()
        generations[packageName] = generation
        return generation
    }

    fun isCurrent(packageName: String?, generation: Long): Boolean {
        val current = packageName?.let { generations[it] }
        return current != null && current == generation
    }

    fun finish(packageName: String?, generation: Long) {
        if (packageName != null) generations.remove(packageName, generation)
    }

    fun cancel(packageName: String?): Boolean {
        return packageName != null && generations.remove(packageName) != null
    }

    fun cancelAll(): Int {
        val count = generations.size
        generations.clear()
        return count
    }

    fun activePackages(): Int = generations.size
}
