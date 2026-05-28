package com.byd.dashcast.beta;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * BetaProxyMetrics — v1.2.78 Couche 4.
 *
 * Persistent counters for the BetaProxy daemon recovery path. Stored in a
 * dedicated SharedPreferences file (process-shared, atomic apply() writes)
 * so the values survive process kills and OOMs — exactly the situations
 * Phase A defense-in-depth is meant to detect.
 *
 * Cheap by design: every {@link #inc(Context, String)} is one int read,
 * one int write, one apply() (async commit). Safe to call from any
 * thread, no static cache, no allocations.
 *
 * Reset only happens when the user taps the "Reset metrics" action from
 * SysInfo (not exposed yet — keep the data around forever by default so
 * we can spot long-term drift).
 */
public final class BetaProxyMetrics {

    private BetaProxyMetrics() {}

    private static final String PREFS = "beta_proxy_metrics";

    /** Successful cold-spawn via app_process64. */
    public static final String K_COLD_SPAWNS    = "cold_spawns";
    /** REBROADCAST fast-path hit — daemon already alive, just trigger file touched. */
    public static final String K_REBROADCASTS   = "rebroadcasts";
    /** Bootstrap returned ERR_NO_APK (post-OTA window before PM has indexed us). */
    public static final String K_FAILS_NO_APK   = "fails_no_apk";
    /** Bootstrap completed but no PROXY_CONNECTED broadcast within timeout. */
    public static final String K_FAILS_TIMEOUT  = "fails_timeout";
    /** Bootstrap shell errored out (ADB off, dex2oat crash, SELinux, …). */
    public static final String K_FAILS_OTHER    = "fails_other";
    /** Cached binder went dead and was cleared by sDeath or a failed transact. */
    public static final String K_BINDER_ZOMBIES = "binder_zombies";

    public static void inc(Context ctx, String key) {
        if (ctx == null || key == null) return;
        SharedPreferences sp = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit().putInt(key, sp.getInt(key, 0) + 1).apply();
    }

    public static int get(Context ctx, String key) {
        if (ctx == null || key == null) return 0;
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(key, 0);
    }

    /** Multiline snapshot suitable for diagnostic reports. */
    public static String snapshot(Context ctx) {
        if (ctx == null) return "<no context>";
        SharedPreferences sp = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        sb.append("cold_spawns    = ").append(sp.getInt(K_COLD_SPAWNS,    0)).append('\n');
        sb.append("rebroadcasts   = ").append(sp.getInt(K_REBROADCASTS,   0)).append('\n');
        sb.append("fails_no_apk   = ").append(sp.getInt(K_FAILS_NO_APK,   0)).append('\n');
        sb.append("fails_timeout  = ").append(sp.getInt(K_FAILS_TIMEOUT,  0)).append('\n');
        sb.append("fails_other    = ").append(sp.getInt(K_FAILS_OTHER,    0)).append('\n');
        sb.append("binder_zombies = ").append(sp.getInt(K_BINDER_ZOMBIES, 0));
        return sb.toString();
    }

    public static void reset(Context ctx) {
        if (ctx == null) return;
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().clear().apply();
    }
}
