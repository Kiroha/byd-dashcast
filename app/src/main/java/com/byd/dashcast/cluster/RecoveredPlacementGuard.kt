package com.byd.dashcast.cluster

import com.byd.dashcast.proxy.daemon.TaskLaunchRecovery

/** Automatic resize/re-anchor must not undo the daemon's verified primary reuse. */
internal class RecoveredPlacementGuard {
    @Volatile private var placement: Pair<String, Int>? = null

    fun record(packageName: String?, displayId: Int, transcript: String?) {
        placement = if (!packageName.isNullOrEmpty() && displayId > 0 &&
            TaskLaunchRecovery.hasRecoveredPrimary(transcript)) packageName to displayId else null
    }

    fun preserves(packageName: String?, displayId: Int): Boolean {
        val current = placement ?: return false
        return current.first == packageName && current.second == displayId
    }

    fun clear() { placement = null }
}
