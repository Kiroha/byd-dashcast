package com.byd.dashcast.data.prefs;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ClusterPrefs — single source of truth for all SharedPreferences access.
 *
 * <p><b>Problem solved:</b> The codebase previously had ~15 {@code PREF_*} string
 * constants duplicated across {@code MainActivity}, {@code BootReceiver},
 * {@code SettingsActivity}, {@code BetaConfig}, and {@code Platform}. Each class
 * called {@code getSharedPreferences(PREFS_NAME, MODE_PRIVATE)} directly, making
 * it impossible to audit which keys existed or rename them safely.
 *
 * <p><b>Architecture role:</b> Data layer — the only class allowed to touch
 * {@code SharedPreferences} for cluster/UI state. Domain objects and UI read
 * through this class rather than accessing raw prefs.
 *
 * <p><b>Key compatibility:</b> All string keys match the values already stored on
 * deployed devices. Changing a key name would silently discard persisted state.
 * Key constants are kept package-private to prevent callers from by-passing this class.
 */
public final class ClusterPrefs {

    /** Shared prefs file used by all DashCast components. */
    public static final String PREFS_NAME = "byd_app_prefs";

    // ── Cluster session state ────────────────────────────────────────────────
    static final String KEY_MAIN_PKG             = "main_display_pkg";
    static final String KEY_CLUSTER_PKG          = "cluster_active_pkg";
    static final String KEY_CLUSTER_NAME         = "cluster_active_name";
    static final String KEY_LAST_CLUSTER_PKG     = "last_cluster_pkg";
    static final String KEY_LAST_CLUSTER_NAME    = "last_cluster_name";
    static final String KEY_SESSION_CLUSTER_PKGS = "session_cluster_pkgs";
    static final String KEY_AUTO_LAUNCH_PKG      = "auto_launch_pkg";

    // ── Hardware config ──────────────────────────────────────────────────────
    /**
     * sendInfo code for cluster screen size.
     * 29 = 8.8" (Atto 3, Dolphin…), 30 = 12.3" (Seal EU, default), 31 = 10.25" (Seal U DMI).
     */
    public static final String KEY_CLUSTER_TYPE   = "cluster_screen_size_cmd";
    public static final int    CLUSTER_TYPE_DEFAULT = 30;

    // ── UI preferences ───────────────────────────────────────────────────────
    static final String KEY_FAVORITES            = "favorites";
    static final String KEY_GRID_MODE            = "grid_mode";
    static final String KEY_FIRST_LAUNCH_TIP     = "first_launch_tip_shown";
    static final String KEY_IME_BANNER_DISMISSED = "ime_a11y_banner_dismissed";
    static final String KEY_HUD_BANNER_DISMISSED = "hud_notif_banner_dismissed";

    // ── Startup behaviour ────────────────────────────────────────────────────
    public static final String KEY_BOOT_AUTO_START = "boot_auto_start_enabled";
    // ── Projection start behaviour ───────────────────────────────────────────
    /**
     * When false (default): projection start sends only sendInfo(16) + sendInfo(0),
     * leaving the cluster screen size unchanged.
     * When true: sendInfo(30) is prepended — fixes ADAS window stretching on
     * some screen sizes but causes a visible shape change on others.
     */
    public static final String KEY_ADAS_WINDOW_FIX = "adas_window_fix";

    // ── Voice ASR model ──────────────────────────────────────────────
    /** true = high-accuracy large model (~1.3 GB), false = small model (~40 MB, default). */
    public static final String KEY_VOSK_HIGH_ACCURACY = "vosk_high_accuracy";

    // ── Fission layout automation ─────────────────────────────────────────────
    /** When true: favourite layout is activated automatically when FissionActivity opens,
     *  and all bound apps are launched immediately. */
    public static final String KEY_FISSION_AUTO_LAYOUT     = "fission_auto_layout";
    /** When true: VD slots for the favourite layout are pre-created when FissionActivity
     *  opens, so the first launch is instant. Apps are NOT started automatically. */
    public static final String KEY_FISSION_PRECREATE_SLOTS = "fission_precreate_slots";

    private ClusterPrefs() { /* static utility class */ }

    // ─────────────────────────────────────────────────────────────────────────
    // Cluster session — current active projection
    // ─────────────────────────────────────────────────────────────────────────

    public static String getMainPkg(Context ctx) {
        return prefs(ctx).getString(KEY_MAIN_PKG, null);
    }

    public static void setMainPkg(Context ctx, String pkg) {
        edit(ctx).putString(KEY_MAIN_PKG, pkg).apply();
    }

    public static String getClusterPkg(Context ctx) {
        return prefs(ctx).getString(KEY_CLUSTER_PKG, null);
    }

    public static void setClusterPkg(Context ctx, String pkg) {
        edit(ctx).putString(KEY_CLUSTER_PKG, pkg).apply();
    }

    public static String getClusterName(Context ctx) {
        return prefs(ctx).getString(KEY_CLUSTER_NAME, null);
    }

    public static void setClusterName(Context ctx, String name) {
        edit(ctx).putString(KEY_CLUSTER_NAME, name).apply();
    }

    /** Atomically persists both the last-used cluster package and its display name. */
    public static void setLastCluster(Context ctx, String pkg, String name) {
        edit(ctx)
                .putString(KEY_LAST_CLUSTER_PKG, pkg)
                .putString(KEY_LAST_CLUSTER_NAME, name)
                .apply();
    }

    public static String getLastClusterPkg(Context ctx) {
        return prefs(ctx).getString(KEY_LAST_CLUSTER_PKG, null);
    }

    public static String getLastClusterName(Context ctx) {
        return prefs(ctx).getString(KEY_LAST_CLUSTER_NAME, null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session package tracking
    // Used to move apps back to display 0 when projection stops.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a mutable copy of the persisted set of packages launched on the cluster
     * during the current or previous session. Never returns the live reference so
     * callers cannot accidentally mutate the persisted set.
     */
    public static Set<String> getSessionClusterPkgs(Context ctx) {
        Set<String> raw = prefs(ctx).getStringSet(KEY_SESSION_CLUSTER_PKGS, null);
        return raw == null ? new LinkedHashSet<>() : new LinkedHashSet<>(raw);
    }

    public static void setSessionClusterPkgs(Context ctx, Set<String> pkgs) {
        edit(ctx).putStringSet(KEY_SESSION_CLUSTER_PKGS, pkgs).apply();
    }

    public static void clearSessionClusterPkgs(Context ctx) {
        edit(ctx).remove(KEY_SESSION_CLUSTER_PKGS).apply();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Auto-launch
    // ─────────────────────────────────────────────────────────────────────────

    public static String getAutoLaunchPkg(Context ctx) {
        return prefs(ctx).getString(KEY_AUTO_LAUNCH_PKG, null);
    }

    public static void setAutoLaunchPkg(Context ctx, String pkg) {
        if (pkg == null) {
            edit(ctx).remove(KEY_AUTO_LAUNCH_PKG).apply();
        } else {
            edit(ctx).putString(KEY_AUTO_LAUNCH_PKG, pkg).apply();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hardware config
    // ─────────────────────────────────────────────────────────────────────────

    public static int getClusterType(Context ctx) {
        return prefs(ctx).getInt(KEY_CLUSTER_TYPE, CLUSTER_TYPE_DEFAULT);
    }

    public static void setClusterType(Context ctx, int type) {
        edit(ctx).putInt(KEY_CLUSTER_TYPE, type).apply();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI preferences
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a mutable copy of the favorites set. Never returns the live reference
     * (SharedPreferences contract: the returned set must not be modified in-place).
     */
    public static Set<String> getFavorites(Context ctx) {
        Set<String> raw = prefs(ctx).getStringSet(KEY_FAVORITES, null);
        return raw == null ? new HashSet<>() : new HashSet<>(raw);
    }

    public static void setFavorites(Context ctx, Set<String> favorites) {
        edit(ctx).putStringSet(KEY_FAVORITES, favorites).apply();
    }

    public static boolean isGridMode(Context ctx) {
        return prefs(ctx).getBoolean(KEY_GRID_MODE, false);
    }

    public static boolean isGridMode(Context ctx, boolean defaultValue) {
        return prefs(ctx).getBoolean(KEY_GRID_MODE, defaultValue);
    }

    public static void setGridMode(Context ctx, boolean gridMode) {
        edit(ctx).putBoolean(KEY_GRID_MODE, gridMode).apply();
    }

    public static boolean isFirstLaunchTipShown(Context ctx) {
        return prefs(ctx).getBoolean(KEY_FIRST_LAUNCH_TIP, false);
    }

    public static void setFirstLaunchTipShown(Context ctx) {
        edit(ctx).putBoolean(KEY_FIRST_LAUNCH_TIP, true).apply();
    }

    public static boolean isImeBannerDismissed(Context ctx) {
        return prefs(ctx).getBoolean(KEY_IME_BANNER_DISMISSED, false);
    }

    public static void setImeBannerDismissed(Context ctx) {
        edit(ctx).putBoolean(KEY_IME_BANNER_DISMISSED, true).apply();
    }

    public static boolean isHudBannerDismissed(Context ctx) {
        return prefs(ctx).getBoolean(KEY_HUD_BANNER_DISMISSED, false);
    }

    public static void setHudBannerDismissed(Context ctx) {
        edit(ctx).putBoolean(KEY_HUD_BANNER_DISMISSED, true).apply();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Startup behaviour
    // ─────────────────────────────────────────────────────────────────────────

    public static boolean isBootAutoStartEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_BOOT_AUTO_START, false);
    }

    public static void setBootAutoStartEnabled(Context ctx, boolean enabled) {
        edit(ctx).putBoolean(KEY_BOOT_AUTO_START, enabled).apply();
    }

    // ───────────────────────────────────────────────────────────────────────
    // Projection start behaviour
    // ───────────────────────────────────────────────────────────────────────

    public static boolean isAdasWindowFixEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ADAS_WINDOW_FIX, false);
    }

    public static void setAdasWindowFixEnabled(Context ctx, boolean enabled) {
        edit(ctx).putBoolean(KEY_ADAS_WINDOW_FIX, enabled).apply();
    }

    // ───────────────────────────────────────────────────────────────────────
    // Voice ASR model
    // ───────────────────────────────────────────────────────────────────────

    public static boolean isVoskHighAccuracy(Context ctx) {
        return prefs(ctx).getBoolean(KEY_VOSK_HIGH_ACCURACY, false);
    }

    public static void setVoskHighAccuracy(Context ctx, boolean highAccuracy) {
        edit(ctx).putBoolean(KEY_VOSK_HIGH_ACCURACY, highAccuracy).apply();
    }

    public static boolean isFissionAutoLayout(Context ctx) {
        return prefs(ctx).getBoolean(KEY_FISSION_AUTO_LAYOUT, false);
    }

    public static void setFissionAutoLayout(Context ctx, boolean v) {
        edit(ctx).putBoolean(KEY_FISSION_AUTO_LAYOUT, v).apply();
    }

    public static boolean isFissionPrecreateSlots(Context ctx) {
        return prefs(ctx).getBoolean(KEY_FISSION_PRECREATE_SLOTS, false);
    }

    public static void setFissionPrecreateSlots(Context ctx, boolean v) {
        edit(ctx).putBoolean(KEY_FISSION_PRECREATE_SLOTS, v).apply();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-app launch counters (used for popularity sorting)
    // ─────────────────────────────────────────────────────────────────────────

    private static final String KEY_LAUNCH_COUNT_PREFIX = "launch_count_";

    public static void incrementLaunchCount(Context ctx, String pkgName) {
        if (pkgName == null) return;
        String key = KEY_LAUNCH_COUNT_PREFIX + pkgName;
        SharedPreferences p = prefs(ctx);
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Quick-switch recent apps
    // ─────────────────────────────────────────────────────────────────────────

    private static final String KEY_RECENT_APPS = "recent_cluster_apps";
    private static final int    MAX_RECENT_APPS  = 3;

    /** Prepends {@code pkgName|appName} to the recent-apps list, capped at {@link #MAX_RECENT_APPS}. */
    public static void addRecentApp(Context ctx, String pkgName, String appName) {
        SharedPreferences p = prefs(ctx);
        String raw = p.getString(KEY_RECENT_APPS, "");
        java.util.LinkedList<String> entries = new java.util.LinkedList<>();
        if (!raw.isEmpty()) {
            for (String e : raw.split(";;")) entries.add(e);
        }
        String newEntry = pkgName + "|" + appName;
        entries.remove(newEntry);
        entries.addFirst(newEntry);
        while (entries.size() > MAX_RECENT_APPS) entries.removeLast();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(";;");
            sb.append(entries.get(i));
        }
        p.edit().putString(KEY_RECENT_APPS, sb.toString()).apply();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Always uses the Application context to avoid Activity leaks. */
    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                  .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static SharedPreferences.Editor edit(Context ctx) {
        return prefs(ctx).edit();
    }
}
