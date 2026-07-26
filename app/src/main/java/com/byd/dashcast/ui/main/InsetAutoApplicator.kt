package com.byd.dashcast.ui.main

import android.content.Context
import android.os.Handler
import android.os.Looper

import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.ui.settings.SettingsActivity
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.util.concurrent.LifecycleGate
import java.util.regex.Pattern

/**
 * Re-applies a persisted per-app hand-drawn rectangle 500 ms after
 * a successful cluster launch, so the user doesn't have to press Apply every time.
 *
 * Owns the background resize thread so there is at most one concurrent resize
 * operation regardless of how quickly the user switches apps.
 *
 * LOT 4 — retry logic (up to 3 × 500 ms) guards against the Waze taskId race
 * where a freshly-launched task is absent from {@code dumpsys activity recents}
 * for up to ~1 s after {@code am start}.
 */
class InsetAutoApplicator(private val mHost: Host) {

    interface Host {
        fun getContext(): Context
        /** Returns the package currently active on the cluster, or null. */
        fun getCurrentPkg(): String?
        /** Returns the bound ClusterService, or null if not bound. */
        fun getClusterServiceIfBound(): ClusterService?
    }

    private val mHandler = Handler(Looper.getMainLooper())
    private val mLifecycleGate = LifecycleGate()
    @Volatile private var mResizeThread: Thread? = null

    /**
     * Checks whether {@code pkg} has per-app insets saved, and if so schedules
     * their application 500 ms later. No-op if insets match the global defaults.
     */
    fun apply(pkg: String?) {
        if (pkg == null) return
        val operation = mLifecycleGate.capture()
        if (!operation.isValid) return
        val ctx = mHost.getContext().applicationContext
        val p = ctx.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE)

        // v1.8.2 — a hand-drawn rectangle is now the ONLY thing that shrinks a cluster app.
        // The symmetric per-app/global inset margins are gone: they were applied twice (as launch
        // bounds AND as a display overscan), which removed 40% of the panel on the 80/50 default
        // (INC-20260725-211405). No rectangle = the app fills the panel, which is the default.
        val rect = parseRect(p.getString(SettingsActivity.PREF_CLUSTER_RECT_PREFIX + pkg, null))
        if (rect != null) {
            AppLogger.d(TAG, "autoApplyRect pkg=" + pkg + " [" + rect[0] + "," + rect[1] + ","
                    + rect[2] + "," + rect[3] + "]")
            scheduleRectApply(pkg, rect, operation)
        }
    }

    /**
     * Re-applies a hand-drawn rectangle 500 ms after launch via the daemon moveAndResize
     * path. Retries up to 3× (the FREEFORM flip may not take on the first call right after a
     * fresh launch — the same call succeeds once the task has settled, which is why drawing
     * it manually in ClusterResizeActivity works).
     */
    private fun scheduleRectApply(pkg: String, rect: IntArray,
                                  operation: LifecycleGate.Token) {
        mHandler.postDelayed({
            if (!isCurrent(operation, pkg)) return@postDelayed
            val svc = mHost.getClusterServiceIfBound() ?: return@postDelayed
            val clusterId = svc.displayId
            if (clusterId <= 0) {
                AppLogger.w(TAG, "autoApplyRect: cluster display not connected — skipped")
                return@postDelayed
            }
            val prev = mResizeThread
            if (prev != null && prev.isAlive) {
                AppLogger.d(TAG, "autoApplyRect: resize thread still running, skipped for " + pkg)
                return@postDelayed
            }
            val t = Thread(Runnable {
                if (!operation.isValid) return@Runnable
                // Wait for the freshly-launched task to appear (same race as the inset path).
                var taskId = -1
                for (attempt in 1..3) {
                    taskId = svc.findRunningTaskId(pkg)
                    if (!operation.isValid) return@Runnable
                    if (taskId > 0) break
                    if (!isCurrent(operation, pkg)) return@Runnable
                    try { Thread.sleep(500) }
                    catch (ie: InterruptedException) { Thread.currentThread().interrupt(); return@Runnable }
                }
                if (taskId <= 0) {
                    AppLogger.w(TAG, "autoApplyRect: task not found for " + pkg)
                    return@Runnable
                }
                // Re-apply via the same daemon path ClusterResizeActivity uses; retry until
                // the FREEFORM flip lands (the daemon returns its log, never throws).
                for (attempt in 1..3) {
                    if (!isCurrent(operation, pkg)) return@Runnable
                    try {
                        val log: String? = ProxyClient.moveAndResize(pkg, clusterId,
                                rect[0], rect[1], rect[2], rect[3])
                        AppLogger.i(TAG, "autoApplyRect [" + rect[0] + "," + rect[1] + ","
                                + rect[2] + "," + rect[3] + "] (attempt " + attempt + "/3) → " + log)
                        if (log != null && !log.contains("not allowed") && !log.contains("ERR")) break
                    } catch (th: Throwable) {
                        AppLogger.w(TAG, "autoApplyRect failed: " + th.message)
                    }
                    try { Thread.sleep(800) }
                    catch (ie: InterruptedException) { Thread.currentThread().interrupt(); return@Runnable }
                    if (!operation.isValid) return@Runnable
                }
            }, "auto-rect-resize-thread")
            mResizeThread = t
            t.start()
        }, 500L)
    }

    private fun isCurrent(operation: LifecycleGate.Token, pkg: String): Boolean {
        return operation.isValid && pkg == mHost.getCurrentPkg()
    }

    /** Permanently cancels delayed callbacks and interrupts the owned resize worker. */
    fun destroy() {
        mLifecycleGate.invalidate()
        mHandler.removeCallbacksAndMessages(null)
        val worker = mResizeThread
        worker?.interrupt()
        mResizeThread = null
    }

    companion object {
        private const val TAG = "InsetAutoApplicator"

        /** Parses a persisted "l,t,r,b" rectangle, or null if absent/invalid. */
        private fun parseRect(s: String?): IntArray? {
            if (s == null) return null
            val parts = Pattern.compile(",").split(s)
            if (parts.size != 4) return null
            return try {
                intArrayOf(
                        parts[0].trim().toInt(), parts[1].trim().toInt(),
                        parts[2].trim().toInt(), parts[3].trim().toInt()
                )
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
