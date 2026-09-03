package com.byd.dashcast.proxy

import android.content.Context
import androidx.core.content.edit

/**
 * ProxyMetrics — v1.2.78 Couche 4.
 *
 * Persistent counters for the BetaProxy daemon recovery path. Stored in a
 * dedicated SharedPreferences file (process-shared, atomic apply() writes)
 * so the values survive process kills and OOMs — exactly the situations
 * Phase A defense-in-depth is meant to detect.
 *
 * Cheap by design: every [inc] is one int read,
 * one int write, one apply() (async commit). Safe to call from any
 * thread, no static cache, no allocations.
 *
 * Reset only happens when the user taps the "Reset metrics" action from
 * SysInfo (not exposed yet — keep the data around forever by default so
 * we can spot long-term drift).
 */
object ProxyMetrics {

    private const val PREFS = "beta_proxy_metrics"

    /** Successful cold-spawn via app_process64. */
    const val K_COLD_SPAWNS: String = "cold_spawns"
    /** REBROADCAST fast-path hit — daemon already alive, just trigger file touched. */
    const val K_REBROADCASTS: String = "rebroadcasts"
    /** Bootstrap returned ERR_NO_APK (post-OTA window before PM has indexed us). */
    const val K_FAILS_NO_APK: String = "fails_no_apk"
    /** Bootstrap completed but no PROXY_CONNECTED broadcast within timeout. */
    const val K_FAILS_TIMEOUT: String = "fails_timeout"
    /** Bootstrap shell errored out (ADB off, dex2oat crash, SELinux, …). */
    const val K_FAILS_OTHER: String = "fails_other"
    /** Cached binder went dead and was cleared by sDeath (kernel-notified). */
    const val K_BINDER_ZOMBIES: String = "binder_zombies"
    /** v1.3.3 — Binder transact() threw DeadObjectException although
     *  sDeath had not fired yet (silent death, kernel notif missing or
     *  late). Sites must call [ProxyClient.invalidateBinder] so
     *  the next isConnected() check returns false immediately. */
    const val K_BINDER_DEATHS_SILENT: String = "binder_deaths_silent"

    @JvmStatic
    fun inc(ctx: Context?, key: String?) {
        if (ctx == null || key == null) return
        val sp = ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putInt(key, sp.getInt(key, 0) + 1) }
    }

    @JvmStatic
    fun get(ctx: Context?, key: String?): Int {
        if (ctx == null || key == null) return 0
        return ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(key, 0)
    }

    /** Multiline snapshot suitable for diagnostic reports. */
    @JvmStatic
    fun snapshot(ctx: Context?): String {
        if (ctx == null) return "<no context>"
        val sp = ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sb = StringBuilder()
        sb.append("cold_spawns    = ").append(sp.getInt(K_COLD_SPAWNS, 0)).append('\n')
        sb.append("rebroadcasts   = ").append(sp.getInt(K_REBROADCASTS, 0)).append('\n')
        sb.append("fails_no_apk   = ").append(sp.getInt(K_FAILS_NO_APK, 0)).append('\n')
        sb.append("fails_timeout  = ").append(sp.getInt(K_FAILS_TIMEOUT, 0)).append('\n')
        sb.append("fails_other    = ").append(sp.getInt(K_FAILS_OTHER, 0)).append('\n')
        sb.append("binder_deaths_notif  = ").append(sp.getInt(K_BINDER_ZOMBIES, 0)).append('\n')
        sb.append("binder_deaths_silent = ").append(sp.getInt(K_BINDER_DEATHS_SILENT, 0))
        return sb.toString()
    }

    @JvmStatic
    fun reset(ctx: Context?) {
        if (ctx == null) return
        ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { clear() }
    }
}
