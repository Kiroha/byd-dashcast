package com.byd.dashcast.cluster.dpi

import android.content.Context

import com.byd.dashcast.proxy.ShellGateway
import com.byd.dashcast.util.AppLogger

/**
 * ClusterDpiManager — applies / restores per-app DPI overrides on the cluster
 * display via `wm density N -d <displayId>`.
 *
 * **SAFETY — display 0 (head unit) is NEVER touched.** Every public entry point
 * bails out unless `displayId > 0`. As an additional defence in depth, [ShellGateway]
 * itself blocks any `wm density ... -d 0` via `WM_DISPLAY_ZERO`, so even a programming
 * mistake here cannot reach the head unit.
 *
 * Failures are caught and logged: a DPI tweak must NEVER prevent the app from
 * launching or projection from stopping.
 *
 * @since v1.2.81-beta
 */
object ClusterDpiManager {

    private const val TAG = "ClusterDpiMgr"

    /** Settle delay after a wm density change before issuing am start. */
    const val SETTLE_MS = 150L

    /** displayId → last applied DPI (0 == system default / reset). */
    private val sCache = HashMap<Int, Int>()

    /**
     * Apply the override (if any) for [pkg] on [displayId] before launching it.
     * Pure no-op when `displayId <= 0` (SAFETY: never touch display 0) or when the
     * desired density equals the currently cached one for that display.
     *
     * Returns `true` if a density command was dispatched (caller should wait
     * [SETTLE_MS] ms before issuing `am start`); `false` if already at the desired
     * density (no settle needed).
     */
    @JvmStatic
    fun applyForLaunch(ctx: Context?, pkg: String?, displayId: Int): Boolean {
        if (ctx == null || displayId <= 0) return false // SAFETY: never touch main screen
        try {
            val desired = if (pkg.isNullOrEmpty()) 0 else ClusterDpiPrefs.getDpi(ctx, pkg)
            val currentVal = synchronized(sCache) { sCache[displayId] } ?: 0
            if (currentVal == desired) {
                return false // already in the right state — no shell call needed
            }
            val cmd = if (desired <= 0) "wm density reset -d $displayId"
            else "wm density $desired -d $displayId"
            AppLogger.i(
                TAG, "applyForLaunch pkg=$pkg display=$displayId " +
                    (if (currentVal == 0) "default" else "${currentVal}dpi") +
                    " → " + (if (desired == 0) "default" else "${desired}dpi")
            )
            ShellGateway.execShell(ctx, cmd)
            synchronized(sCache) { sCache[displayId] = desired }
            // Caller is responsible for the SETTLE_MS delay (off the main thread).
            return true
        } catch (t: Throwable) {
            // Never let a DPI tweak block the launch.
            AppLogger.w(TAG, "applyForLaunch failed: " + t.message)
            return false
        }
    }

    /**
     * Restore [displayId] to the system default density. Safe to call even when no
     * override was ever applied (becomes a no-op via the cache). Always guarded by
     * `displayId > 0`.
     */
    @JvmStatic
    fun restore(ctx: Context?, displayId: Int) {
        if (ctx == null || displayId <= 0) return // SAFETY: never touch main screen
        try {
            val currentVal = synchronized(sCache) { sCache[displayId] } ?: 0
            if (currentVal == 0) {
                return // already at default
            }
            AppLogger.i(TAG, "restore display=$displayId (was ${currentVal}dpi)")
            ShellGateway.execShell(ctx, "wm density reset -d $displayId")
            synchronized(sCache) { sCache.remove(displayId) }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "restore failed: " + t.message)
        }
    }

    /** @return current cache snapshot (displayId → applied DPI). For diagnostics. */
    @JvmStatic
    fun cacheSnapshot(): Map<Int, Int> = synchronized(sCache) { HashMap(sCache) }
}
