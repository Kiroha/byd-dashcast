package com.byd.dashcast.ui.main

import com.byd.dashcast.infrastructure.task.TaskLocation

/** Validates saved projection state before adopting, moving or relaunching navigation. */
class DashboardResumeCoordinator(private val tracker: DashboardSelectionTracker) {
    interface Host {
        fun isOwnerCurrent(): Boolean
        fun findLocation(callback: (TaskLocation) -> Unit)
        fun reanchor(isCurrent: () -> Boolean, callback: (Boolean) -> Unit)
        fun onReady()
        fun onAbsent()
        fun onUnknown()
        fun onReanchorFailed()
        fun restoreBootLatch()
    }

    fun resume(packageName: String, generation: Long, fromBoot: Boolean,
               displayId: Int, host: Host) {
        if (!tracker.markPending(packageName, generation, fromBoot)) {
            if (fromBoot) host.restoreBootLatch()
            return
        }
        fun completeCurrent(): Boolean {
            val completed = tracker.complete(packageName, generation)
            if (!completed) return false
            if (host.isOwnerCurrent()) return true
            if (fromBoot) host.restoreBootLatch()
            return false
        }
        host.findLocation { location ->
            if (!completeCurrent()) return@findLocation
            when (location.matchDisplay(displayId)) {
                TaskLocation.DisplayMatch.ON_EXPECTED_DISPLAY -> host.onReady()
                TaskLocation.DisplayMatch.ABSENT -> host.onAbsent()
                TaskLocation.DisplayMatch.UNKNOWN -> {
                    if (fromBoot) host.restoreBootLatch()
                    host.onUnknown()
                }
                TaskLocation.DisplayMatch.ON_OTHER_DISPLAY -> {
                    if (!tracker.markPending(packageName, generation, fromBoot)) return@findLocation
                    host.reanchor({ tracker.isCurrent(generation) }) { success ->
                        if (completeCurrent()) {
                            if (success) host.onReady() else {
                                if (fromBoot) host.restoreBootLatch()
                                host.onReanchorFailed()
                            }
                        }
                    }
                }
            }
        }
    }
}
