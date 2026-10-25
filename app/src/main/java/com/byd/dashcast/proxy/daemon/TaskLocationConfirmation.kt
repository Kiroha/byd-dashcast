package com.byd.dashcast.proxy.daemon

import com.byd.dashcast.infrastructure.task.TaskLocation
import com.byd.dashcast.infrastructure.task.TaskLocationDumpParser

/** Keep expensive corroboration outside task polling and the watchdog. Never cache its authority. */
internal object TaskLocationConfirmation {
    const val TIMEOUT_MS = 1_500L
    const val MAX_OUTPUT_BYTES = 1024 * 1024
    const val COMMAND = "dumpsys activity activities; rc=\$?; " +
        "if [ \"\$rc\" = 0 ]; then printf '\\n${TaskLocationDumpParser.COMPLETION_MARKER}\\n'; fi; exit \"\$rc\""

    fun placementUnconfirmed(api: Int, location: TaskLocation, targetDisplay: Int): Boolean =
        when (location.matchDisplay(targetDisplay)) {
            TaskLocation.DisplayMatch.UNKNOWN -> true
            // Legacy explicit projection still needs its original cross-display placement cascade.
            TaskLocation.DisplayMatch.ON_OTHER_DISPLAY -> api == 33
            else -> false
        }

    fun afterStart(api: Int, started: Boolean, current: () -> Boolean,
                   lookup: () -> TaskLocation, pause: () -> Unit,
                   confirm: (TaskLocation) -> TaskLocation): TaskLocation {
        if (api != 33 && !started) {
            // Preserve the legacy immediate plain-launch path after an explicit framework error.
            if (!current()) return TaskLocation.unknown()
            val location = lookup()
            return if (current()) confirm(location) else TaskLocation.unknown()
        }
        return poll(current, lookup, pause, confirm)
    }

    fun poll(current: () -> Boolean, lookup: () -> TaskLocation, pause: () -> Unit,
             confirm: (TaskLocation) -> TaskLocation): TaskLocation {
        var location = TaskLocation.unknown()
        repeat(16) {
            if (!current()) return TaskLocation.unknown()
            try { pause() }
            catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return TaskLocation.unknown()
            }
            if (!current()) return TaskLocation.unknown()
            location = lookup()
            if (!current()) return TaskLocation.unknown()
            if (location.status == TaskLocation.Status.FOUND && location.displayId >= 0) return location
        }
        return if (current()) confirm(location) else TaskLocation.unknown()
    }

    fun confirm(api: Int, packageName: String, fast: TaskLocation, current: () -> Boolean,
                capture: () -> ProxyShell.Result): TaskLocation {
        if (!current()) return TaskLocation.unknown()
        // The independent dump check targets the observed Android 13 failure, not legacy DiLink 3.
        if (api != 33) return fast
        if (fast.status == TaskLocation.Status.FOUND && fast.displayId >= 0) return fast
        val result = capture()
        if (!current() || result.exit != 0) return TaskLocation.unknown()
        val parsed = TaskLocationDumpParser.parse(result.output, packageName)
        if (fast.status == TaskLocation.Status.FOUND &&
            (parsed.status != TaskLocation.Status.FOUND || parsed.taskId != fast.taskId)) return TaskLocation.unknown()
        return parsed
    }
}
