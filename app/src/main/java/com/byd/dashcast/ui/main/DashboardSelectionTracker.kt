package com.byd.dashcast.ui.main

/** Tracks one pending boot-adoption lookup across taps and Activity/service invalidation. */
class DashboardSelectionTracker {

    class Selection internal constructor(
        val generation: Long,
        val isDuplicatePending: Boolean,
    )

    private var generation: Long = 0
    private var pendingBootPackage: String? = null

    /** Begins a selection, or identifies a duplicate tap for the lookup already in flight. */
    @Synchronized
    fun begin(packageName: String?): Selection {
        if (packageName != null && packageName == pendingBootPackage) {
            return Selection(generation, true)
        }
        pendingBootPackage = null
        return Selection(++generation, false)
    }

    @Synchronized
    fun markBootPending(packageName: String?, expectedGeneration: Long): Boolean {
        if (packageName == null || generation != expectedGeneration) return false
        pendingBootPackage = packageName
        return true
    }

    /** Completes only the current matching lookup and clears its pending state. */
    @Synchronized
    fun completeBoot(packageName: String?, expectedGeneration: Long): Boolean {
        if (generation != expectedGeneration ||
            packageName == null ||
            packageName != pendingBootPackage) {
            return false
        }
        pendingBootPackage = null
        return true
    }

    /** Invalidates callbacks and returns the package that must be restored to the process latch. */
    @Synchronized
    fun takePendingForInvalidation(): String? {
        generation++
        val pending = pendingBootPackage
        pendingBootPackage = null
        return pending
    }
}
