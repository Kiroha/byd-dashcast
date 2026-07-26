package com.byd.dashcast.app

import android.content.ComponentName
import android.content.Context
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
