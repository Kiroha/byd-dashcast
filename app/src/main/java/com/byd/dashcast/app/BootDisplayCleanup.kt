package com.byd.dashcast.app

import android.content.Context
import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.fission.FissionClient
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.infrastructure.task.TaskLocation
import com.byd.dashcast.proxy.DaemonBinderResolver
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.TaskMoveResult
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
        cleanup(context, null)
    }

    internal fun cleanup(context: Context, suppliedOperations: Operations?) {
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
        val operations = suppliedOperations ?: RuntimeOperations(context.applicationContext)
        for (pkg in pkgs) {
            if (cleanPackage(pkg, operations)) {
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

    internal interface Operations {
        fun locate(packageName: String): TaskLocation
        fun moveToDisplayZero(packageName: String): Boolean
    }

    internal fun cleanPackage(packageName: String, operations: Operations): Boolean {
        val before = operations.locate(packageName)
        return when (before.status) {
            TaskLocation.Status.ABSENT -> {
                AppLogger.d(TAG, "No running task for $packageName — cleanup complete")
                true
            }
            TaskLocation.Status.UNKNOWN -> {
                AppLogger.w(TAG, "Task location unknown for $packageName — keeping it pending")
                false
            }
            TaskLocation.Status.FOUND -> {
                if (before.displayId == 0) {
                    AppLogger.d(TAG, "$packageName already on Display 0")
                    true
                } else {
                    val moveAccepted = operations.moveToDisplayZero(packageName)
                    val after = operations.locate(packageName)
                    val landed = after.status == TaskLocation.Status.ABSENT ||
                            (after.status == TaskLocation.Status.FOUND && after.displayId == 0)
                    if (landed) {
                        AppLogger.i(TAG, "Moved $packageName from display ${before.displayId} → Display 0")
                    } else {
                        AppLogger.w(TAG, "Move of $packageName was not verified (accepted="
                                + "$moveAccepted) — keeping it pending")
                    }
                    landed
                }
            }
        }
    }

    private class RuntimeOperations(private val context: Context) : Operations {
        private val proxyReady: Boolean = try {
            ProxyClient.connect(context)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Proxy unavailable for boot cleanup: ${t.message}")
            false
        }
        private var surfaceLookupComplete = false
        private var cachedSurfaceBinder: android.os.IBinder? = null

        override fun locate(packageName: String): TaskLocation {
            if (!proxyReady) return TaskLocation.unknown()
            return try {
                ProxyClient.findTaskLocationForPackage(packageName)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Task lookup failed for $packageName: ${t.message}")
                TaskLocation.unknown()
            }
        }

        override fun moveToDisplayZero(packageName: String): Boolean {
            val binder = awaitSurfaceDaemon() ?: return false
            return TaskMoveResult.isSuccess(FissionClient.moveToDisplay0(binder, packageName))
        }

        private fun awaitSurfaceDaemon(): android.os.IBinder? {
            if (surfaceLookupComplete) return cachedSurfaceBinder
            var binder = DaemonBinderResolver.surfaceDaemonBinder()
            if (binder != null && binder.isBinderAlive) {
                cachedSurfaceBinder = binder
                surfaceLookupComplete = true
                return binder
            }
            AdbLocalClient.startMirrorDaemon(context)
            repeat(SURFACE_DAEMON_POLLS) {
                try {
                    Thread.sleep(SURFACE_DAEMON_POLL_MS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
                binder = DaemonBinderResolver.surfaceDaemonBinder()
                if (binder != null && binder.isBinderAlive) {
                    cachedSurfaceBinder = binder
                    surfaceLookupComplete = true
                    return binder
                }
            }
            surfaceLookupComplete = true
            AppLogger.w(TAG, "SurfaceDaemon unavailable for display-0 cleanup")
            return null
        }
    }

    private const val SURFACE_DAEMON_POLLS = 16
    private const val SURFACE_DAEMON_POLL_MS = 500L
}
