package com.byd.dashcast.proxy;

import android.content.Context;
import android.content.SharedPreferences;

import com.byd.dashcast.data.prefs.ClusterPrefs;

/**
 * DaemonConfig — centralised daemon and projection preferences.
 *
 * <p>The proxy daemon is the default (and only) privileged command path.
 * The only user-visible toggle is {@link #PREF_USE_LEGACY_PATH}: when ON,
 * all projection commands fall back to the legacy ADB (dadb) path and the
 * daemon is bypassed. Use this when the daemon is not reachable or behaving
 * incorrectly.
 */
public final class DaemonConfig {

    private DaemonConfig() {}

    /** When true, all privileged commands route through AdbLocalClient (legacy
     *  dadb path) instead of the proxy daemon. Default OFF (daemon is used). */
    public static final String  PREF_USE_LEGACY_PATH   = "use_legacy_path";
    public static final boolean DEFAULT_USE_LEGACY_PATH = false;

    public static final String  PREF_FISSION_MODE   = "fission_mode_enabled";
    public static final boolean DEFAULT_FISSION_MODE = false;

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                  .getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isLegacyPathEnabled(Context ctx) {
        return prefs(ctx).getBoolean(PREF_USE_LEGACY_PATH, DEFAULT_USE_LEGACY_PATH);
    }

    public static void setLegacyPathEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(PREF_USE_LEGACY_PATH, enabled).apply();
    }

    public static boolean isFissionModeEnabled(Context ctx) {
        return prefs(ctx).getBoolean(PREF_FISSION_MODE, DEFAULT_FISSION_MODE);
    }

    public static void setFissionModeEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(PREF_FISSION_MODE, enabled).apply();
    }
}
