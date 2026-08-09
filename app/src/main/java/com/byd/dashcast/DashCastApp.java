package com.byd.dashcast;

import android.app.Application;
import android.content.Context;
import androidx.appcompat.app.AppCompatDelegate;

import com.byd.dashcast.platform.Platform;
import com.byd.dashcast.util.AppLogger;

public class DashCastApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // appcompat:1.1.0 defaults to MODE_NIGHT_UNSPECIFIED (= always light).
        // Explicitly follow the system dark/light setting so DayNight theme works.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

        // Initialise platform detection once. Reads ro.product.name, Build.* etc.
        // Snapshot is process-wide and immutable. The DiLink-5 override (auto /
        // force-on / force-off) is read from SharedPreferences on demand.
        // Register the application context for ReportChannel before anything can read a
        // credential. Storing a reference is free; the encrypted store itself is opened lazily and
        // warmed off the main thread below, so cold start is unaffected.
        com.byd.dashcast.report.ReportChannel.init(getApplicationContext());

        final Platform p = Platform.get();
        // Build/SystemProperties-derived fields only — none of these touch
        // SharedPreferences, so the line is safe to build synchronously here.
        // effectiveDiLink5 is logged from the worker below instead: resolving it
        // (Platform.isDiLink5 -> readOverride -> prefs().getString) triggers the
        // first byd_app_prefs XML parse, which must not block Application.onCreate.
        AppLogger.i("Platform",
                "product=" + p.rawProductName()
                + " model="   + p.rawModel()
                + " api="     + p.androidApi()
                + " autoDiLink5=" + p.isAutoDetectedDiLink5()
                + " autoDiLink3=" + p.isAutoDetectedDiLink3());

        // Move every SharedPreferences-touching platform step off the main thread.
        // Both isDiLink5(app) (first byd_app_prefs read) and the gate inside
        // primeClusterResizeProbe(app) (isDiLink5 + prefs.contains, Platform.java
        // ~L345-347) would otherwise parse byd_app_prefs.xml synchronously in
        // Application.onCreate. Running them on a short-lived daemon thread keeps
        // the cold-start critical path free of disk I/O. primeClusterResizeProbe
        // is idempotent, forks its own shell worker, and no onCreate step below
        // depends on its result, so the deferral is contract-safe. It early-returns
        // (still off-main) on DL2/DL3/DL4.
        final Context app = getApplicationContext();
        Thread platformInit = new Thread(new Runnable() {
            @Override public void run() {
                // KeyStore IPC + a file read. Off the main thread for the same reason every
                // SharedPreferences touch below is: it must not sit on the cold-start path.
                try { com.byd.dashcast.report.ReportChannel.warm(); }
                catch (Throwable t) { AppLogger.w("ReportChannel", "warm failed: " + t.getMessage()); }
                try {
                    AppLogger.i("Platform", "effectiveDiLink5=" + p.isDiLink5(app));
                    p.primeClusterResizeProbe(app);
                } catch (Throwable t) {
                    // Never let a prefs/probe failure crash the process via the
                    // default uncaught-exception handler on this daemon thread.
                    AppLogger.e("Platform", "platform-init worker failed", t);
                }
            }
        }, "platform-init");
        platformInit.setDaemon(true);
        platformInit.start();

        clearLegacyOverscanPrefs();

        // Foreground fallback monitor for the proxy daemon. It releases its
        // HandlerThread while the stronger always-on keeper below is active.
        com.byd.dashcast.proxy.ProxyWatchdog.install(this);
        // Always-on foreground service that monitors the daemon every 10 s.
        com.byd.dashcast.proxy.ProxyKeeperService.ensureRunning(this);
    }

    /**
     * v1.8.2 — ONE-TIME clean slate for the cluster sizing prefs.
     *
     * <p>The global overscan margins and the symmetric per-app inset seekbars are gone. They were
     * applied twice — once as the task's launch bounds and once as a display-level
     * {@code wm overscan} — so the 80/50 default cost 160/100 px per side pair: a measured
     * 1600x520 of a 1920x720 cluster, 40% of the panel black (INC-20260725-211405).
     *
     * <p>Every stored value must go, not just the global one:
     * <ul>
     *   <li>the global keys would otherwise stay applied with no UI left to clear them;</li>
     *   <li>per-app values were tuned while the global was ALSO shrinking the panel, so they
     *       describe a geometry that no longer exists;</li>
     *   <li>hand-drawn rectangles were drawn in that same doubled world.</li>
     * </ul>
     * Everything therefore restarts full-screen, and a rectangle re-drawn from the app action
     * sheet is now the single mechanism that shrinks anything. The display-level overscan a
     * previous build may have left in WindowManager is cleared separately, on every cluster
     * connect, by {@code ClusterService.onDashboardDisplayConnected}.
     */
    private void clearLegacyOverscanPrefs() {
        try {
            android.content.SharedPreferences p = getSharedPreferences(
                    com.byd.dashcast.data.prefs.ClusterPrefs.PREFS_NAME, MODE_PRIVATE);
            if (p.getBoolean(PREF_OVERSCAN_RESET_DONE, false)) return;
            android.content.SharedPreferences.Editor e = p.edit();
            e.remove("overscan_inset_h").remove("overscan_inset_v").remove("visual_overscan_mode");
            int perApp = 0;
            for (String key : p.getAll().keySet()) {
                if (key == null) continue;
                if (key.startsWith("inset_h_") || key.startsWith("inset_v_")
                        || key.startsWith("cluster_rect_")) {
                    e.remove(key);
                    perApp++;
                }
            }
            e.putBoolean(PREF_OVERSCAN_RESET_DONE, true).apply();
            AppLogger.i("DashCastApp", "cluster sizing reset to full-screen: cleared global "
                    + "overscan + " + perApp + " per-app entries (one-time)");
        } catch (Throwable t) {
            // A prefs failure must never prevent the app from starting.
            AppLogger.w("DashCastApp", "legacy overscan cleanup failed: " + t.getMessage());
        }
    }

    /** Guard so the clean slate above runs exactly once per install, not on every launch. */
    private static final String PREF_OVERSCAN_RESET_DONE = "overscan_cleared_v182";

    /**
     * Cooperative memory shedding. The Application owns exactly one process-wide
     * cache — the AppLogger diagnostic ring buffer. Per-Activity caches (the
     * AppRepository app-list/icon cache, held on the live MainActivity instance)
     * and lazily-held voice-engine state live on their owners, which each receive
     * their own ComponentCallbacks2.onTrimMemory from the framework and must shed
     * there; the Application holds no reference to those live instances and
     * deliberately does not forward to avoid clearing state in flight.
     *
     * <p>Only TRIM_MEMORY_COMPLETE triggers the shed: at that level the process is
     * already backgrounded and next in line for the low-memory killer, so
     * reclaiming the buffer can keep the resident set below the LMK threshold and
     * avoid a kill -> cold-restart cycle. The buffer would be lost on the kill
     * anyway, and COMPLETE is never delivered while the app is foreground, so this
     * never destroys a diagnostic capture the user is about to share.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) {
            shedProcessWideCaches();
        }
    }

    /**
     * Legacy pre-API-14 low-memory signal. Equivalent to
     * onTrimMemory(TRIM_MEMORY_COMPLETE); both may fire and AppLogger.clear() is
     * idempotent, so routing them through the same shed path is safe.
     */
    @Override
    public void onLowMemory() {
        super.onLowMemory();
        shedProcessWideCaches();
    }

    /** Releases the only process-wide cache the Application owns. */
    private void shedProcessWideCaches() {
        AppLogger.clear();
    }
}
