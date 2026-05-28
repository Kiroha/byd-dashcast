package com.byd.dashcast;

import android.app.Application;
import androidx.appcompat.app.AppCompatDelegate;

import com.byd.dashcast.platform.Platform;

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
        Platform p = Platform.get();
        AppLogger.i("Platform",
                "product=" + p.rawProductName()
                + " model="   + p.rawModel()
                + " api="     + p.androidApi()
                + " autoDiLink5=" + p.isAutoDetectedDiLink5()
                + " effectiveDiLink5=" + p.isDiLink5(this));
        // Prime the DL5 cluster-resize capability probe off the main thread so
        // the UI (MainActivity resize button) and ClusterService.resizeActiveTask
        // can read the cached result synchronously without forking a shell on
        // the hot path. No-op on DL2/DL3/DL4. See doc_api/DL5_CLUSTER_RESIZE_LIMITATION.md.
        p.primeClusterResizeProbe(this);

        // v1.2.62-beta — Phase A step 2: foreground liveness ping for the
        // proxy daemon. Idempotent, gated on BetaConfig.isProxyDaemonEnabled,
        // zero overhead while daemon is healthy.
        com.byd.dashcast.beta.ProxyWatchdog.install(this);

        // v1.2.73-beta — Phase A step 4, Couche 2: always-on foreground
        // service that monitors the daemon every 10 s, regardless of
        // whether any activity is in the foreground. The service itself
        // checks the BetaConfig flag at every heartbeat, so users who
        // haven't opted in see no extra notification (the service starts
        // but the heartbeat early-returns).
        if (com.byd.dashcast.beta.BetaConfig.isProxyDaemonEnabled(this)) {
            com.byd.dashcast.beta.ProxyKeeperService.ensureRunning(this);
        }
    }
}
