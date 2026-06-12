package com.byd.dashcast.ui.main;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.byd.dashcast.AdbLocalClient;
import com.byd.dashcast.AppLogger;
import com.byd.dashcast.cluster.ClusterService;
import com.byd.dashcast.ui.settings.SettingsActivity;
import com.byd.dashcast.data.prefs.ClusterPrefs;
import com.byd.dashcast.proxy.ShellGateway;

/**
 * Applies persisted per-app insets (wm overscan + resizeActiveTask) 500 ms after
 * a successful cluster launch, so the user doesn't have to press Apply every time.
 *
 * Owns the background resize thread so there is at most one concurrent resize
 * operation regardless of how quickly the user switches apps.
 *
 * LOT 4 — retry logic (up to 3 × 500 ms) guards against the Waze taskId race
 * where a freshly-launched task is absent from {@code dumpsys activity recents}
 * for up to ~1 s after {@code am start}.
 */
public final class InsetAutoApplicator {

    private static final String TAG = "InsetAutoApplicator";

    public interface Host {
        Context getContext();
        /** Returns the package currently active on the cluster, or null. */
        String getCurrentPkg();
        /** Returns the bound ClusterService, or null if not bound. */
        ClusterService getClusterServiceIfBound();
    }

    private final Host    mHost;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private volatile Thread mResizeThread;

    public InsetAutoApplicator(Host host) {
        mHost = host;
    }

    /**
     * Checks whether {@code pkg} has per-app insets saved, and if so schedules
     * their application 500 ms later. No-op if insets match the global defaults.
     */
    public void apply(final String pkg) {
        if (pkg == null) return;
        Context ctx = mHost.getContext();
        SharedPreferences p = ctx.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE);
        int defH    = p.getInt(SettingsActivity.PREF_INSET_H, SettingsActivity.DEFAULT_INSET_H);
        int defV    = p.getInt(SettingsActivity.PREF_INSET_V, SettingsActivity.DEFAULT_INSET_V);
        int savedH  = p.getInt(SettingsActivity.PREF_INSET_H_PREFIX + pkg, defH);
        int savedV  = p.getInt(SettingsActivity.PREF_INSET_V_PREFIX + pkg, defV);
        if (savedH == defH && savedV == defV) return;
        AppLogger.d(TAG, "autoApplyInsets pkg=" + pkg + " h=" + savedH + " v=" + savedV);

        mHandler.postDelayed(() -> {
            if (!pkg.equals(mHost.getCurrentPkg())) return;
            ClusterService svc = mHost.getClusterServiceIfBound();
            if (svc == null) return;

            int clusterId = svc.getDisplayId();
            if (clusterId > 0) {
                if (AdbLocalClient.isDiLink5Safe(ctx)) {
                    AppLogger.d(TAG, "DL5: skipping wm overscan (API 30+ removed) — resizeTask handles it");
                } else {
                    ShellGateway.execShell(ctx,
                            "wm overscan " + savedH + "," + savedV + "," + savedH + "," + savedV
                                    + " -d " + clusterId);
                }
            } else {
                AppLogger.w(TAG, "autoApplyInsets: cluster display not connected — wm overscan skipped");
            }

            Thread prev = mResizeThread;
            if (prev != null && prev.isAlive()) {
                AppLogger.d(TAG, "autoApplyInsets: resize thread still running, skipped for " + pkg);
                return;
            }
            Thread t = new Thread(() -> {
                int taskId = -1;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    taskId = svc.findRunningTaskId(pkg);
                    if (taskId > 0) break;
                    if (!pkg.equals(mHost.getCurrentPkg())) return;
                    AppLogger.d(TAG, "autoApplyInsets: taskId<=0 for " + pkg
                            + " (attempt " + attempt + "/3) — retrying in 500 ms");
                    try { Thread.sleep(500); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
                svc.resizeActiveTask(taskId, pkg);
            }, "auto-resize-thread");
            mResizeThread = t;
            t.start();
        }, 500);
    }
}
