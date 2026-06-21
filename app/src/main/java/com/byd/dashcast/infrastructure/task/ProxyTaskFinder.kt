package com.byd.dashcast.infrastructure.task

import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.util.AppLogger

import java.util.regex.Pattern

/**
 * Paths 2 + 2b: daemon-side `dumpsys activity recents` and
 * `dumpsys activity activities` via the shell-uid proxy.
 *
 * Requires the proxy daemon to be connected ([ProxyClient.isConnected]).
 * Uses the same regex logic migrated verbatim from `ClusterService` to
 * preserve identical parse behaviour across all BYD ROM variants.
 */
class ProxyTaskFinder : TaskFinder {

    @Throws(TaskFinder.TaskFinderException::class)
    override fun findTaskId(packageName: String): Int {
        if (!ProxyClient.isConnected()) {
            throw TaskFinder.TaskFinderException("daemon not connected")
        }
        // Path 2 — dumpsys activity recents
        try {
            val out = ProxyClient.runShell("dumpsys activity recents")
            if (!out.isNullOrEmpty()) {
                val id = parseFromRecents(out, packageName)
                if (id != TaskFinder.NOT_FOUND) return id
                AppLogger.d(TAG, "$packageName not in recents (len=${out.length})")
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "recents dump failed: ${t.message}")
        }
        // Path 2b — dumpsys activity activities (launcher-agnostic fallback)
        try {
            val out = ProxyClient.runShell("dumpsys activity activities")
            if (!out.isNullOrEmpty()) {
                val id = parseFromActivities(out, packageName)
                if (id != TaskFinder.NOT_FOUND) return id
                AppLogger.d(TAG, "$packageName not in activities (len=${out.length})")
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "activities dump failed: ${t.message}")
        }
        return TaskFinder.NOT_FOUND
    }

    companion object {
        private const val TAG = "ProxyTaskFinder"

        // Pre-compiled patterns — same as ClusterService originals (zero logic change).
        private val P_TASK_ID = Pattern.compile("Task\\{[^}]*#(\\d+)")
        private val P_RECENT_SPLIT = Pattern.compile("(?m)^\\s*\\* Recent #\\d+:\\s*")

        // ── Parsers (migrated verbatim from ClusterService static methods) ─────────

        @JvmStatic
        fun parseFromRecents(dump: String?, packageName: String?): Int {
            if (dump == null || packageName == null) return TaskFinder.NOT_FOUND
            // Fast path — affinity on the same Task{...} line.
            try {
                val p = Pattern.compile(
                    "Task\\{[^}]*#(\\d+)[^}]*\\bA=" + Pattern.quote(packageName) + "\\b"
                )
                val m = p.matcher(dump)
                if (m.find()) {
                    try {
                        return m.group(1)!!.toInt()
                    } catch (ignored: NumberFormatException) {
                    }
                }
            } catch (ignored: Exception) {
            }

            // Block scan — realActivity= or cmp= match anywhere inside the Task block.
            try {
                val blocks = P_RECENT_SPLIT.split(dump)
                val marker1 = "realActivity=$packageName/"
                val marker2 = "cmp=$packageName/"
                val marker3 = " A=$packageName "
                for (block in blocks) {
                    if (block.isNullOrEmpty()) continue
                    if (block.contains(marker1) || block.contains(marker2) || block.contains(marker3)) {
                        val mm = P_TASK_ID.matcher(block)
                        if (mm.find()) {
                            try {
                                return mm.group(1)!!.toInt()
                            } catch (ignored: NumberFormatException) {
                            }
                        }
                    }
                }
            } catch (ignored: Exception) {
            }
            return TaskFinder.NOT_FOUND
        }

        @JvmStatic
        fun parseFromActivities(dump: String?, packageName: String?): Int {
            if (dump == null || packageName == null) return TaskFinder.NOT_FOUND
            try {
                val p = Pattern.compile(
                    "ActivityRecord\\{[^}]*\\s" + Pattern.quote(packageName) + "/[^\\s}]+\\st(\\d+)\\}"
                )
                val m = p.matcher(dump)
                if (m.find()) {
                    try {
                        return m.group(1)!!.toInt()
                    } catch (ignored: NumberFormatException) {
                    }
                }
            } catch (ignored: Exception) {
            }
            return TaskFinder.NOT_FOUND
        }
    }
}
