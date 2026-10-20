package com.byd.dashcast.ui.main

/** Tracks one pending projection lookup across taps and Activity/service invalidation. */
class DashboardSelectionTracker {

    class Selection internal constructor(
        val generation: Long,
        val isDuplicatePending: Boolean,
    )

    private var generation: Long = 0
    private var pendingPackage: String? = null
    private var restoreBootLatch = false

    /** Begins a selection, or identifies a duplicate tap for the lookup already in flight. */
    @Synchronized
    fun begin(packageName: String?): Selection {
        if (packageName != null && packageName == pendingPackage) {
            return Selection(generation, true)
        }
        pendingPackage = null
        restoreBootLatch = false
        return Selection(++generation, false)
    }

    @Synchronized
    fun markPending(packageName: String?, expectedGeneration: Long, fromBoot: Boolean = false): Boolean {
        if (packageName == null || generation != expectedGeneration) return false
        pendingPackage = packageName
        restoreBootLatch = fromBoot
        return true
    }

    /** Completes only the current matching lookup and clears its pending state. */
    @Synchronized
    fun complete(packageName: String?, expectedGeneration: Long): Boolean {
        if (generation != expectedGeneration ||
            packageName == null ||
            packageName != pendingPackage) {
            return false
        }
        pendingPackage = null
        restoreBootLatch = false
        return true
    }

    @Synchronized
    fun isCurrent(expectedGeneration: Long): Boolean = generation == expectedGeneration

    /** Invalidates callbacks and returns the package that must be restored to the process latch. */
    @Synchronized
    fun takePendingForInvalidation(): String? {
        generation++
        val pending = if (restoreBootLatch) pendingPackage else null
        pendingPackage = null
        restoreBootLatch = false
        return pending
    }
}
