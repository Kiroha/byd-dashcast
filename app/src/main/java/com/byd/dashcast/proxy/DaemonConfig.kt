package com.byd.dashcast.proxy

import android.content.Context
import android.content.SharedPreferences

import androidx.core.content.edit

import com.byd.dashcast.data.prefs.ClusterPrefs

/**
 * DaemonConfig — centralised daemon and projection preferences.
 *
 * The proxy daemon is the default (and only) privileged command path. The only
 * user-visible toggle is [PREF_USE_LEGACY_PATH]: when ON, all projection commands
 * fall back to the legacy ADB (dadb) path and the daemon is bypassed. Use this when
 * the daemon is not reachable or behaving incorrectly.
 */
object DaemonConfig {

    /** When true, all privileged commands route through AdbLocalClient (legacy
     *  dadb path) instead of the proxy daemon. Default OFF (daemon is used). */
    const val PREF_USE_LEGACY_PATH = "use_legacy_path"
    const val DEFAULT_USE_LEGACY_PATH = false

    const val PREF_FISSION_MODE = "fission_mode_enabled"
    const val DEFAULT_FISSION_MODE = false

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE)

    @JvmStatic
    fun isLegacyPathEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(PREF_USE_LEGACY_PATH, DEFAULT_USE_LEGACY_PATH)

    @JvmStatic
    fun setLegacyPathEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit { putBoolean(PREF_USE_LEGACY_PATH, enabled) }
    }

    @JvmStatic
    fun isFissionModeEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(PREF_FISSION_MODE, DEFAULT_FISSION_MODE)

    @JvmStatic
    fun setFissionModeEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit { putBoolean(PREF_FISSION_MODE, enabled) }
    }
}
