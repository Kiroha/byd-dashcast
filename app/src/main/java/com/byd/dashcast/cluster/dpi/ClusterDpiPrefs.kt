package com.byd.dashcast.cluster.dpi

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

import java.util.TreeMap

/**
 * ClusterDpiPrefs — persistent per-package DPI override store for cluster launches.
 *
 * Storage: a dedicated SharedPreferences file `cluster_dpi_per_app`.
 * Each entry maps a package name to a positive integer in [96, 480].
 * Absence (or any non-positive stored value) means "system default" → the
 * driver must call `wm density reset -d <displayId>` when restoring.
 *
 * NEVER persists anything tied to display 0 (head unit); this class only
 * stores per-package preferences. The displayId guard lives in [ClusterDpiManager].
 *
 * @since v1.2.81-beta
 */
object ClusterDpiPrefs {

    private const val PREFS = "cluster_dpi_per_app"

    /** Lower / upper bounds for an override entry (matches `wm density` accepted range). */
    const val MIN_DPI = 96
    const val MAX_DPI = 480

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * @return the stored override for [pkg], or `0` when no override is set
     *         (caller must treat 0 as "system default" / wm density reset).
     */
    @JvmStatic
    fun getDpi(ctx: Context?, pkg: String?): Int {
        if (ctx == null || pkg.isNullOrEmpty()) return 0
        val v = sp(ctx).getInt(pkg, 0)
        if (v < MIN_DPI || v > MAX_DPI) return 0
        return v
    }

    /** Stores or clears the override for [pkg]. Pass 0 to remove. */
    @JvmStatic
    fun setDpi(ctx: Context?, pkg: String?, dpi: Int) {
        if (ctx == null || pkg.isNullOrEmpty()) return
        sp(ctx).edit {
            if (dpi <= 0) {
                remove(pkg)
            } else {
                putInt(pkg, dpi.coerceIn(MIN_DPI, MAX_DPI))
            }
        }
    }

    /** @return a sorted snapshot of all overrides (pkg → dpi). Never null. */
    @JvmStatic
    fun snapshot(ctx: Context?): Map<String, Int> {
        val out = TreeMap<String, Int>()
        if (ctx == null) return out
        val all = sp(ctx).all ?: return out
        for ((key, value) in all) {
            if (value is Int && value >= MIN_DPI && value <= MAX_DPI) {
                out[key] = value
            }
        }
        return out
    }
}
