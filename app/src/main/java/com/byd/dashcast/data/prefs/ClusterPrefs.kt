package com.byd.dashcast.data.prefs

import android.content.Context
import android.content.SharedPreferences
import java.util.LinkedList

/**
 * ClusterPrefs — single source of truth for all SharedPreferences access.
 *
 * **Problem solved:** The codebase previously had ~15 `PREF_*` string
 * constants duplicated across `MainActivity`, `BootReceiver`,
 * `SettingsActivity`, `BetaConfig`, and `Platform`. Each class
 * called `getSharedPreferences(PREFS_NAME, MODE_PRIVATE)` directly, making
 * it impossible to audit which keys existed or rename them safely.
 *
 * **Architecture role:** Data layer — the only class allowed to touch
 * `SharedPreferences` for cluster/UI state. Domain objects and UI read
 * through this class rather than accessing raw prefs.
 *
 * **Key compatibility:** All string keys match the values already stored on
 * deployed devices. Changing a key name would silently discard persisted state.
 * Key constants are kept private to prevent callers from by-passing this class.
 */
object ClusterPrefs {

    /** Shared prefs file used by all DashCast components. */
    const val PREFS_NAME = "byd_app_prefs"

    // ── Cluster session state ────────────────────────────────────────────────
    private const val KEY_MAIN_PKG = "main_display_pkg"
    private const val KEY_CLUSTER_PKG = "cluster_active_pkg"
    private const val KEY_CLUSTER_NAME = "cluster_active_name"
    private const val KEY_LAST_CLUSTER_PKG = "last_cluster_pkg"
    private const val KEY_LAST_CLUSTER_NAME = "last_cluster_name"
    private const val KEY_SESSION_CLUSTER_PKGS = "session_cluster_pkgs"
    private const val KEY_AUTO_LAUNCH_PKG = "auto_launch_pkg"

    // ── Hardware config ──────────────────────────────────────────────────────
    /**
     * sendInfo code for cluster screen size.
     * 29 = 8.8" (Atto 3, Dolphin…), 30 = 12.3" (Seal EU, default), 31 = 10.25" (Seal U DMI).
     */
    const val KEY_CLUSTER_TYPE = "cluster_screen_size_cmd"
    const val CLUSTER_TYPE_DEFAULT = 30

    // ── UI preferences ───────────────────────────────────────────────────────
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_GRID_MODE = "grid_mode"
    private const val KEY_FIRST_LAUNCH_TIP = "first_launch_tip_shown"
    private const val KEY_IME_BANNER_DISMISSED = "ime_a11y_banner_dismissed"
    private const val KEY_HUD_BANNER_DISMISSED = "hud_notif_banner_dismissed"

    // ── Startup behaviour ────────────────────────────────────────────────────
    const val KEY_BOOT_AUTO_START = "boot_auto_start_enabled"

    // ── Projection start behaviour ───────────────────────────────────────────
    /**
     * When false (default): projection start sends only sendInfo(16) + sendInfo(0),
     * leaving the cluster screen size unchanged.
     * When true: sendInfo(30) is prepended — fixes ADAS window stretching on
     * some screen sizes but causes a visible shape change on others.
     */
    const val KEY_ADAS_WINDOW_FIX = "adas_window_fix"

    /** Diagnostic opt-in: log the RAW nav-notification text to calibrate Waze/Maps parsing. */
    const val KEY_NAV_RAW_CAPTURE = "nav_raw_capture"

    // ── Voice ASR model ──────────────────────────────────────────────
    /** true = high-accuracy large model (~1.3 GB), false = small model (~40 MB, default). */
    const val KEY_VOSK_HIGH_ACCURACY = "vosk_high_accuracy"

    // ── Fission layout automation ─────────────────────────────────────────────
    /** When true: favourite layout is activated automatically when FissionActivity opens,
     *  and all bound apps are launched immediately. */
    const val KEY_FISSION_AUTO_LAYOUT = "fission_auto_layout"

    /** When true: VD slots for the favourite layout are pre-created when FissionActivity
     *  opens, so the first launch is instant. Apps are NOT started automatically. */
    const val KEY_FISSION_PRECREATE_SLOTS = "fission_precreate_slots"

    // ─────────────────────────────────────────────────────────────────────────
    // Cluster session — current active projection
    // ─────────────────────────────────────────────────────────────────────────

    @JvmStatic
    fun getMainPkg(ctx: Context): String? = prefs(ctx).getString(KEY_MAIN_PKG, null)

    @JvmStatic
    fun setMainPkg(ctx: Context, pkg: String?) {
        edit(ctx).putString(KEY_MAIN_PKG, pkg).apply()
    }

    @JvmStatic
    fun getClusterPkg(ctx: Context): String? = prefs(ctx).getString(KEY_CLUSTER_PKG, null)

    @JvmStatic
    fun setClusterPkg(ctx: Context, pkg: String?) {
        edit(ctx).putString(KEY_CLUSTER_PKG, pkg).apply()
    }

    @JvmStatic
    fun getClusterName(ctx: Context): String? = prefs(ctx).getString(KEY_CLUSTER_NAME, null)

    @JvmStatic
    fun setClusterName(ctx: Context, name: String?) {
        edit(ctx).putString(KEY_CLUSTER_NAME, name).apply()
    }

    /** Atomically persists both the last-used cluster package and its display name. */
    @JvmStatic
    fun setLastCluster(ctx: Context, pkg: String?, name: String?) {
        edit(ctx)
            .putString(KEY_LAST_CLUSTER_PKG, pkg)
            .putString(KEY_LAST_CLUSTER_NAME, name)
            .apply()
    }

    @JvmStatic
    fun getLastClusterPkg(ctx: Context): String? = prefs(ctx).getString(KEY_LAST_CLUSTER_PKG, null)

    @JvmStatic
    fun getLastClusterName(ctx: Context): String? = prefs(ctx).getString(KEY_LAST_CLUSTER_NAME, null)

    // ─────────────────────────────────────────────────────────────────────────
    // Session package tracking
    // Used to move apps back to display 0 when projection stops.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a mutable copy of the persisted set of packages launched on the cluster
     * during the current or previous session. Never returns the live reference so
     * callers cannot accidentally mutate the persisted set.
     */
    @JvmStatic
    fun getSessionClusterPkgs(ctx: Context): MutableSet<String> {
        val raw = prefs(ctx).getStringSet(KEY_SESSION_CLUSTER_PKGS, null)
        return if (raw == null) LinkedHashSet() else LinkedHashSet(raw)
    }

    @JvmStatic
    fun setSessionClusterPkgs(ctx: Context, pkgs: Set<String>) {
        edit(ctx).putStringSet(KEY_SESSION_CLUSTER_PKGS, pkgs).apply()
    }

    @JvmStatic
    fun clearSessionClusterPkgs(ctx: Context) {
        edit(ctx).remove(KEY_SESSION_CLUSTER_PKGS).apply()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Auto-launch
    // ─────────────────────────────────────────────────────────────────────────

    @JvmStatic
    fun getAutoLaunchPkg(ctx: Context): String? = prefs(ctx).getString(KEY_AUTO_LAUNCH_PKG, null)

    @JvmStatic
    fun setAutoLaunchPkg(ctx: Context, pkg: String?) {
        if (pkg == null) {
            edit(ctx).remove(KEY_AUTO_LAUNCH_PKG).apply()
        } else {
            edit(ctx).putString(KEY_AUTO_LAUNCH_PKG, pkg).apply()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hardware config
    // ─────────────────────────────────────────────────────────────────────────

    @JvmStatic
    fun getClusterType(ctx: Context): Int = prefs(ctx).getInt(KEY_CLUSTER_TYPE, CLUSTER_TYPE_DEFAULT)

    @JvmStatic
    fun setClusterType(ctx: Context, type: Int) {
        edit(ctx).putInt(KEY_CLUSTER_TYPE, type).apply()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI preferences
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a mutable copy of the favorites set. Never returns the live reference
     * (SharedPreferences contract: the returned set must not be modified in-place).
     */
    @JvmStatic
    fun getFavorites(ctx: Context): MutableSet<String> {
        val raw = prefs(ctx).getStringSet(KEY_FAVORITES, null)
        return if (raw == null) HashSet() else HashSet(raw)
    }

    @JvmStatic
    fun setFavorites(ctx: Context, favorites: Set<String>) {
        edit(ctx).putStringSet(KEY_FAVORITES, favorites).apply()
    }

    @JvmStatic
    @JvmOverloads
    fun isGridMode(ctx: Context, defaultValue: Boolean = false): Boolean =
        prefs(ctx).getBoolean(KEY_GRID_MODE, defaultValue)

    @JvmStatic
    fun setGridMode(ctx: Context, gridMode: Boolean) {
        edit(ctx).putBoolean(KEY_GRID_MODE, gridMode).apply()
    }

    @JvmStatic
    fun isFirstLaunchTipShown(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_FIRST_LAUNCH_TIP, false)

    @JvmStatic
    fun setFirstLaunchTipShown(ctx: Context) {
        edit(ctx).putBoolean(KEY_FIRST_LAUNCH_TIP, true).apply()
    }

    @JvmStatic
    fun isImeBannerDismissed(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_IME_BANNER_DISMISSED, false)

    @JvmStatic
    fun setImeBannerDismissed(ctx: Context) {
        edit(ctx).putBoolean(KEY_IME_BANNER_DISMISSED, true).apply()
    }

    @JvmStatic
    fun isHudBannerDismissed(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_HUD_BANNER_DISMISSED, false)

    @JvmStatic
    fun setHudBannerDismissed(ctx: Context) {
        edit(ctx).putBoolean(KEY_HUD_BANNER_DISMISSED, true).apply()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Startup behaviour
    // ─────────────────────────────────────────────────────────────────────────

    @JvmStatic
    fun isBootAutoStartEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_BOOT_AUTO_START, false)

    @JvmStatic
    fun setBootAutoStartEnabled(ctx: Context, enabled: Boolean) {
        edit(ctx).putBoolean(KEY_BOOT_AUTO_START, enabled).apply()
    }

    // ───────────────────────────────────────────────────────────────────────
    // Projection start behaviour
    // ───────────────────────────────────────────────────────────────────────

    @JvmStatic
    fun isAdasWindowFixEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ADAS_WINDOW_FIX, false)

    @JvmStatic
    fun setAdasWindowFixEnabled(ctx: Context, enabled: Boolean) {
        edit(ctx).putBoolean(KEY_ADAS_WINDOW_FIX, enabled).apply()
    }

    /**
     * Diagnostic opt-in: when true, [com.byd.dashcast.hud.MapNotificationListenerService] also logs
     * the RAW notification title/text/bigText (clipped) so a maintainer can see exactly what Waze (or
     * any nav app) posts, to calibrate the text parser. OFF by default — the raw text is location PII
     * (destination / current road / ETA) and flows into bug reports, so it must be turned on
     * deliberately from the Diagnostics screen and off again after capturing a route.
     */
    @JvmStatic
    fun isNavRawCaptureEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_NAV_RAW_CAPTURE, false)

    @JvmStatic
    fun setNavRawCaptureEnabled(ctx: Context, enabled: Boolean) {
        edit(ctx).putBoolean(KEY_NAV_RAW_CAPTURE, enabled).apply()
    }

    // ───────────────────────────────────────────────────────────────────────
    // Voice ASR model
    // ───────────────────────────────────────────────────────────────────────

    @JvmStatic
    fun isVoskHighAccuracy(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_VOSK_HIGH_ACCURACY, false)

    @JvmStatic
    fun setVoskHighAccuracy(ctx: Context, highAccuracy: Boolean) {
        edit(ctx).putBoolean(KEY_VOSK_HIGH_ACCURACY, highAccuracy).apply()
    }

    @JvmStatic
    fun isFissionAutoLayout(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_FISSION_AUTO_LAYOUT, false)

    @JvmStatic
    fun setFissionAutoLayout(ctx: Context, v: Boolean) {
        edit(ctx).putBoolean(KEY_FISSION_AUTO_LAYOUT, v).apply()
    }

    @JvmStatic
    fun isFissionPrecreateSlots(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_FISSION_PRECREATE_SLOTS, false)

    @JvmStatic
    fun setFissionPrecreateSlots(ctx: Context, v: Boolean) {
        edit(ctx).putBoolean(KEY_FISSION_PRECREATE_SLOTS, v).apply()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-app launch counters (used for popularity sorting)
    // ─────────────────────────────────────────────────────────────────────────

    private const val KEY_LAUNCH_COUNT_PREFIX = "launch_count_"

    @JvmStatic
    fun incrementLaunchCount(ctx: Context, pkgName: String?) {
        if (pkgName == null) return
        val key = KEY_LAUNCH_COUNT_PREFIX + pkgName
        val p = prefs(ctx)
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Quick-switch recent apps
    // ─────────────────────────────────────────────────────────────────────────

    private const val KEY_RECENT_APPS = "recent_cluster_apps"
    private const val MAX_RECENT_APPS = 3

    /** Prepends `pkgName|appName` to the recent-apps list, capped at [MAX_RECENT_APPS]. */
    @JvmStatic
    fun addRecentApp(ctx: Context, pkgName: String?, appName: String?) {
        val p = prefs(ctx)
        val raw = p.getString(KEY_RECENT_APPS, "") ?: ""
        val entries = LinkedList<String>()
        if (raw.isNotEmpty()) {
            for (e in raw.split(";;")) entries.add(e)
        }
        val newEntry = "$pkgName|$appName"
        entries.remove(newEntry)
        entries.addFirst(newEntry)
        while (entries.size > MAX_RECENT_APPS) entries.removeLast()
        val sb = StringBuilder()
        for (i in entries.indices) {
            if (i > 0) sb.append(";;")
            sb.append(entries[i])
        }
        p.edit().putString(KEY_RECENT_APPS, sb.toString()).apply()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Always uses the Application context to avoid Activity leaks. */
    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun edit(ctx: Context): SharedPreferences.Editor = prefs(ctx).edit()
}
