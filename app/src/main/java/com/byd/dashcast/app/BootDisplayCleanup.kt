package com.byd.dashcast.app

import android.content.ComponentName
import android.content.Context
import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.util.AppLogger

/**
 * Boot/onCreate safety net: moves cluster-affined apps back to Display 0
 * using IActivityTaskManager reflection (no ClusterService needed).
 * Only runs when boot_auto_start_enabled is false.
 */
object BootDisplayCleanup {

    private const val TAG = "BootDisplayCleanup"

    @JvmStatic
    fun cleanup(context: Context) {
        // Liveness guard (AUD-006). session_cluster_pkgs is NOT a leftover from a previous
        // session: ClusterSessionTracker.persist() rewrites it after every mutation, so while a
        // projection is live it holds the packages currently ON the cluster. Running the cleanup
        // then would move the driver's navigation off the cluster mid-drive. ClusterService owns
        // the cluster task lifecycle for its whole lifetime, so if it is alive we must not touch
        // those tasks behind its back. Skipping is the safe direction: apps stay on the cluster
        // and the next genuine cleanup still catches them.
        if (ClusterService.isRunning()) {
            AppLogger.i(TAG, "ClusterService is alive — skipping cleanup (projection owns these tasks)")
            return
        }
        val pkgs = ClusterPrefs.getSessionClusterPkgs(context)
        if (pkgs.isEmpty()) {
            AppLogger.d(TAG, "No session cluster packages to clean up")
            return
        }
        val remaining = HashSet(pkgs)
        AppLogger.i(TAG, "Cleaning up " + pkgs.size + " apps → Display 0: " + pkgs)
        for (pkg in pkgs) {
            if (moveTaskToDisplayZero(pkg)) {
                remaining.remove(pkg)
            }
        }
        if (remaining.isEmpty()) {
            ClusterPrefs.clearSessionClusterPkgs(context)
        } else {
            ClusterPrefs.setSessionClusterPkgs(context, remaining)
            AppLogger.w(TAG, "Cleanup partially failed, keeping pending set: $remaining")
        }
    }

    /**
     * KNOWN GAP, deliberately left open. This reflects into
     * `IActivityTaskManager.moveTaskToDisplay`, which DiLink 3 strips — INC-20260815-181820 prints
     * `moveTaskToDisplay stripped on ROM` on the very car whose app was stuck — so on that platform
     * this safety net has never once done anything.
     *
     * v1.8.29 tried to fix it by relaunching the package on display 0 instead. That was worse than
     * the gap: `cleanup()` walks a session HISTORY rather than a live inventory, this method matches
     * on package name alone with no idea which display the task is on, and both callers fire it
     * unprompted — opening DashCast, and `BOOT_COMPLETED`, which this ROM re-delivers at every
     * ACC-on without a reboot. The driver could get several apps cold-launched onto the centre
     * screen at ignition. Reverted.
     *
     * Doing this properly needs positive evidence that a task exists on a non-zero display, and
     * `getTasks()` here is uid-filtered so the app process cannot see it — it has to come from the
     * uid-2000 daemon. That is its own change, not a corollary of this one.
     */
    private fun moveTaskToDisplayZero(packageName: String): Boolean {
        return try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val iatm = atmClass.getMethod("getService").invoke(null)
            val tasks = iatm.javaClass.getMethod("getTasks", Int::class.javaPrimitiveType)
                    .invoke(iatm, 100) as List<*>?
                ?: return false
            for (taskInfo in tasks) {
                val base = taskInfo!!.javaClass.getField("baseActivity").get(taskInfo) as ComponentName?
                if (base != null && packageName == base.packageName) {
                    val taskId = taskInfo.javaClass.getField("taskId").getInt(taskInfo)
                    iatm.javaClass.getMethod("moveTaskToDisplay",
                            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(iatm, taskId, 0)
                    AppLogger.i(TAG, "Moved $packageName (taskId=$taskId) → Display 0")
                    return true
                }
            }
            AppLogger.d(TAG, "No running task found for $packageName — already gone, skipping")
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not move $packageName to Display 0: " + e.message)
            false
        }
    }
}
