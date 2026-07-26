package com.byd.dashcast.ui.main

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import androidx.core.content.edit
import com.byd.dashcast.R
import com.byd.dashcast.data.prefs.ClusterPrefs

/**
 * Tracks how long each cluster app is displayed and provides a summary dialog.
 * State ({@code mStartTime}) and persistence ({@code usage_ms_<pkg>} in ClusterPrefs)
 * are fully owned here; MainActivity just calls {@link #trackStart()}, {@link #trackStop},
 * and {@link #showStatsDialog()}.
 */
class UsageTracker(private val mHost: Host) {

    interface Host {
        fun getContext(): Context
    }

    private var mStartTime: Long = 0

    fun trackStart() {
        mStartTime = System.currentTimeMillis()
    }

    fun trackStop(pkgName: String?) {
        if (mStartTime <= 0 || pkgName == null) return
        val elapsed = System.currentTimeMillis() - mStartTime
        mStartTime = 0
        if (elapsed < 1000) return
        val prefs = mHost.getContext()
            .getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        val prev = prefs.getLong("usage_ms_$pkgName", 0)
        prefs.edit { putLong("usage_ms_$pkgName", prev + elapsed) }
    }

    fun showStatsDialog() {
        val ctx = mHost.getContext()
        val prefs = ctx.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        val all = prefs.all
        val stats = ArrayList<Array<String>>()
        for (entry in all.entries) {
            val value = entry.value
            if (entry.key.startsWith("usage_ms_") && value is Long) {
                val pkg = entry.key.substring("usage_ms_".length)
                val ms = value
                var name = pkg
                try {
                    val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
                    val label: CharSequence? = ctx.packageManager.getApplicationLabel(ai)
                    if (label != null) name = label.toString()
                } catch (ignored: Exception) {
                }
                stats.add(arrayOf(name, formatDuration(ms)))
            }
        }
        if (stats.isEmpty()) {
            Toast.makeText(
                ctx, ctx.getString(R.string.usage_empty),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        stats.sortWith { a, b -> a[0].compareTo(b[0], ignoreCase = true) }
        val sb = StringBuilder()
        for (s in stats) sb.append(s[0]).append(" — ").append(s[1]).append("\n")

        AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.usage_title))
            .setMessage(sb.toString().trim())
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(ctx.getString(R.string.usage_reset)) { _, _ ->
                prefs.edit {
                    for (key in prefs.all.keys) {
                        if (key.startsWith("usage_ms_")) remove(key)
                    }
                }
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.toast_usage_stats_reset),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    companion object {
        private fun formatDuration(ms: Long): String {
            val seconds = ms / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            if (hours > 0) return "${hours}h ${minutes % 60}m"
            if (minutes > 0) return "${minutes}m ${seconds % 60}s"
            return "${seconds}s"
        }
    }
}
