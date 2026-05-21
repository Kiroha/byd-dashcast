package com.byd.dashcast.beta;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * BetaConfig — centralised access to the experimental "Beta Engine" preferences.
 *
 * <p>Two independent toggles, both default OFF (legacy execution path is always
 * used when both are off). Toggles take effect at the next app launch — see
 * {@code SettingsActivity} for the user-facing restart dialog.
 *
 * <ul>
 *   <li>{@link #PREF_USE_PROXY_DAEMON} — Component A: run privileged commands
 *       through a Binder daemon hosted in a dedicated {@code app_process}
 *       spawned via local ADB (UID 2000), instead of one-shot {@code dadb}
 *       shell calls. Inspired by OpenBYD's daemon architecture; the
 *       DashCast implementation exposes its own {@code @dashcast_proxy}
 *       abstract socket.</li>
 *   <li>{@link #PREF_USE_SYSTEM_CONTEXT} — Component B: obtain a system-uid
 *       {@code Context} via reflection on
 *       {@code ActivityThread.systemMain().getSystemContext()} and use it as
 *       the parameter for {@code BYDAuto*Device.getInstance(...)} to bypass
 *       the SDK's internal permission self-check. Inspired by
 *       {@code external_code/BydAgent.java}.</li>
 * </ul>
 *
 * <p>Both flags are independently testable in the Diag screen ("Beta Engine"
 * tab). At runtime, any failure of the beta path must fall back to the legacy
 * path transparently — see {@link BetaEngineGateway}.
 */
public final class BetaConfig {

    private BetaConfig() {}

    private static final String PREFS_NAME = "byd_app_prefs"; // same file as SettingsActivity.PREFS_NAME

    public static final String  PREF_USE_PROXY_DAEMON   = "beta_use_proxy_daemon";
    public static final boolean DEFAULT_USE_PROXY_DAEMON = false;

    public static final String  PREF_USE_SYSTEM_CONTEXT   = "beta_use_system_context";
    public static final boolean DEFAULT_USE_SYSTEM_CONTEXT = false;

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                  .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isProxyDaemonEnabled(Context ctx) {
        return prefs(ctx).getBoolean(PREF_USE_PROXY_DAEMON, DEFAULT_USE_PROXY_DAEMON);
    }

    public static boolean isSystemContextEnabled(Context ctx) {
        return prefs(ctx).getBoolean(PREF_USE_SYSTEM_CONTEXT, DEFAULT_USE_SYSTEM_CONTEXT);
    }

    public static void setProxyDaemonEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(PREF_USE_PROXY_DAEMON, enabled).apply();
    }

    public static void setSystemContextEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(PREF_USE_SYSTEM_CONTEXT, enabled).apply();
    }

    /** True if at least one experimental flag is enabled. */
    public static boolean anyEnabled(Context ctx) {
        return isProxyDaemonEnabled(ctx) || isSystemContextEnabled(ctx);
    }
}
