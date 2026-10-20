package com.byd.dashcast.proxy.daemon

import com.byd.dashcast.cluster.display.ClusterDisplayInfo
import com.byd.dashcast.infrastructure.SurfaceDaemonStartCommand
import com.byd.dashcast.infrastructure.task.TaskLocation

/** SX361 can reuse its empty primary through the explicit-display launch path. */
internal object EmptyPrimaryStackRecovery {
    const val FINGERPRINT = "BYD-AUTO/DiLink3.0/DiLink3.0:10/QKQ1.210910.001/eng.build.20260610.082733:user/release-keys"

    data class Stack(val id: Int, val displayId: Int, val mode: Int, val type: Int,
                     val taskIds: List<Int>?)

    data class Snapshot(val api: Int, val fingerprint: String, val userId: Int,
                        val display: ClusterDisplayInfo?, val displayMode: Int,
                        val activitySupportsSplit: Boolean, val location: TaskLocation,
                        val stacks: List<Stack>?)

    interface Operations {
        fun isCurrent(): Boolean
        fun snapshot(): Snapshot?
        fun launchPrimary()
    }

    fun isKnownFirmware(api: Int, fingerprint: String): Boolean =
        api == 29 && fingerprint == FINGERPRINT

    private fun eligible(snapshot: Snapshot, displayId: Int): Boolean {
        val display = snapshot.display ?: return false
        return isKnownFirmware(snapshot.api, snapshot.fingerprint) &&
            snapshot.userId == 0 && snapshot.activitySupportsSplit &&
            displayId > 0 && display.id == displayId && snapshot.displayMode == 1 &&
            display.name == "fission_bg_xdjaVirtualSurface" &&
            display.ownerPackage == "com.xdja.containerservice" && display.ownerUid == 1000 &&
            !display.isPrivate && display.state == "ON"
    }

    fun selectPrimary(snapshot: Snapshot, displayId: Int): Int? {
        if (!eligible(snapshot, displayId) || snapshot.location.status != TaskLocation.Status.ABSENT) {
            return null
        }
        val stacks = snapshot.stacks ?: return null
        // An unreadable display id could hide another live stack on our target.
        if (stacks.any { it.displayId < 0 }) return null
        val target = stacks.filter { it.displayId == displayId }
        if (target.any { it.id <= 0 || it.mode !in 1..5 || it.type !in 0..4 ||
                    it.taskIds == null || it.taskIds.isNotEmpty() }) return null
        val primary = target.filter { it.mode == 3 }.singleOrNull() ?: return null
        return primary.id.takeIf { primary.type == 1 }
    }

    fun confirmsPrimary(snapshot: Snapshot, displayId: Int, primaryId: Int): Boolean {
        if (!eligible(snapshot, displayId) ||
            snapshot.location.matchDisplay(displayId) != TaskLocation.DisplayMatch.ON_EXPECTED_DISPLAY) {
            return false
        }
        val primary = snapshot.stacks?.singleOrNull { it.id == primaryId } ?: return false
        return primary.displayId == displayId && primary.mode == 3 && primary.type == 1 &&
            primary.taskIds?.contains(snapshot.location.taskId) == true
    }

    /** One command only; cancellation after metadata reads must not resurrect a released session. */
    fun recover(displayId: Int, operations: Operations): TaskLocation? {
        if (!operations.isCurrent()) return null
        val before = operations.snapshot() ?: return null
        val primaryId = selectPrimary(before, displayId) ?: return null
        if (!operations.isCurrent()) return null
        operations.launchPrimary()
        val after = operations.snapshot() ?: return null
        if (!operations.isCurrent() || !confirmsPrimary(after, displayId, primaryId)) return null
        return after.location
    }

    fun launchCommand(component: String, displayId: Int): String =
        "am start-activity -W --user 0 --windowingMode 3 --display $displayId" +
            " --activity-no-animation -n " + SurfaceDaemonStartCommand.quote(component)
}
