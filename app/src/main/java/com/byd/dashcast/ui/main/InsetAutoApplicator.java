package com.byd.dashcast.ui.main;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.byd.dashcast.infrastructure.AdbLocalClient;
import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.cluster.ClusterService;
import com.byd.dashcast.ui.settings.SettingsActivity;
import com.byd.dashcast.data.prefs.ClusterPrefs;
import com.byd.dashcast.proxy.ProxyClient;
import com.byd.dashcast.proxy.ShellGateway;
import com.byd.dashcast.util.concurrent.LifecycleGate;

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
    private final LifecycleGate mLifecycleGate = new LifecycleGate();
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
        final LifecycleGate.Token operation = mLifecycleGate.capture();
        if (!operation.isValid()) return;
        Context ctx = mHost.getContext().getApplicationContext();
        SharedPreferences p = ctx.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE);

        // A hand-drawn rectangle (ClusterResizeActivity) takes precedence over the symmetric
        // seekbar insets: re-apply it via the daemon moveAndResize path that actually works on
        // DL3 (the inset/resizeActiveTask path is rejected with "resizeTask not allowed").
        int[] rect = parseRect(p.getString(SettingsActivity.PREF_CLUSTER_RECT_PREFIX + pkg, null));
        if (rect != null) {
            AppLogger.d(TAG, "autoApplyRect pkg=" + pkg + " [" + rect[0] + "," + rect[1] + ","
                    + rect[2] + "," + rect[3] + "]");
            scheduleRectApply(pkg, rect, operation);
            return;
        }

        int defH    = p.getInt(SettingsActivity.PREF_INSET_H, SettingsActivity.DEFAULT_INSET_H);
        int defV    = p.getInt(SettingsActivity.PREF_INSET_V, SettingsActivity.DEFAULT_INSET_V);
        int savedH  = p.getInt(SettingsActivity.PREF_INSET_H_PREFIX + pkg, defH);
        int savedV  = p.getInt(SettingsActivity.PREF_INSET_V_PREFIX + pkg, defV);
        if (savedH == defH && savedV == defV) return;
        AppLogger.d(TAG, "autoApplyInsets pkg=" + pkg + " h=" + savedH + " v=" + savedV);

        mHandler.postDelayed(() -> {
            if (!isCurrent(operation, pkg)) return;
            ClusterService svc = mHost.getClusterServiceIfBound();
            if (svc == null) return;

            int clusterId = svc.getDisplayId();
            if (clusterId > 0) {
                if (AdbLocalClient.isDiLink5Safe(ctx)) {
                    AppLogger.d(TAG, "DL5: skipping wm overscan (API 30+ removed) — resizeTask handles it");
                } else {
                    if (!operation.isValid()) return;
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
                if (!operation.isValid()) return;
                int taskId = -1;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    taskId = svc.findRunningTaskId(pkg);
                    if (!operation.isValid()) return;
                    if (taskId > 0) break;
                    if (!isCurrent(operation, pkg)) return;
                    AppLogger.d(TAG, "autoApplyInsets: taskId<=0 for " + pkg
                            + " (attempt " + attempt + "/3) — retrying in 500 ms");
                    try { Thread.sleep(500); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
                if (!isCurrent(operation, pkg)) return;
                svc.resizeActiveTask(taskId, pkg);
            }, "auto-resize-thread");
            mResizeThread = t;
            t.start();
        }, 500);
    }

    /**
     * Re-applies a hand-drawn rectangle 500 ms after launch via the daemon moveAndResize
     * path. Retries up to 3× (the FREEFORM flip may not take on the first call right after a
     * fresh launch — the same call succeeds once the task has settled, which is why drawing
     * it manually in ClusterResizeActivity works).
     */
    private void scheduleRectApply(final String pkg, final int[] rect,
                                   final LifecycleGate.Token operation) {
        mHandler.postDelayed(() -> {
            if (!isCurrent(operation, pkg)) return;
            ClusterService svc = mHost.getClusterServiceIfBound();
            if (svc == null) return;
            final int clusterId = svc.getDisplayId();
            if (clusterId <= 0) {
                AppLogger.w(TAG, "autoApplyRect: cluster display not connected — skipped");
                return;
            }
            Thread prev = mResizeThread;
            if (prev != null && prev.isAlive()) {
                AppLogger.d(TAG, "autoApplyRect: resize thread still running, skipped for " + pkg);
                return;
            }
            Thread t = new Thread(() -> {
                if (!operation.isValid()) return;
                // Wait for the freshly-launched task to appear (same race as the inset path).
                int taskId = -1;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    taskId = svc.findRunningTaskId(pkg);
                    if (!operation.isValid()) return;
                    if (taskId > 0) break;
                    if (!isCurrent(operation, pkg)) return;
                    try { Thread.sleep(500); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
                if (taskId <= 0) {
                    AppLogger.w(TAG, "autoApplyRect: task not found for " + pkg);
                    return;
                }
                // Re-apply via the same daemon path ClusterResizeActivity uses; retry until
                // the FREEFORM flip lands (the daemon returns its log, never throws).
                for (int attempt = 1; attempt <= 3; attempt++) {
                    if (!isCurrent(operation, pkg)) return;
                    try {
                        String log = ProxyClient.moveAndResize(pkg, clusterId,
                                rect[0], rect[1], rect[2], rect[3]);
                        AppLogger.i(TAG, "autoApplyRect [" + rect[0] + "," + rect[1] + ","
                                + rect[2] + "," + rect[3] + "] (attempt " + attempt + "/3) → " + log);
                        if (log != null && !log.contains("not allowed") && !log.contains("ERR")) break;
                    } catch (Throwable th) {
                        AppLogger.w(TAG, "autoApplyRect failed: " + th.getMessage());
                    }
                    try { Thread.sleep(800); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    if (!operation.isValid()) return;
                }
            }, "auto-rect-resize-thread");
            mResizeThread = t;
            t.start();
        }, 500);
    }

    private boolean isCurrent(LifecycleGate.Token operation, String pkg) {
        return operation.isValid() && pkg.equals(mHost.getCurrentPkg());
    }

    /** Permanently cancels delayed callbacks and interrupts the owned resize worker. */
    public void destroy() {
        mLifecycleGate.invalidate();
        mHandler.removeCallbacksAndMessages(null);
        Thread worker = mResizeThread;
        if (worker != null) worker.interrupt();
        mResizeThread = null;
    }

    /** Parses a persisted "l,t,r,b" rectangle, or null if absent/invalid. */
    private static int[] parseRect(String s) {
        if (s == null) return null;
        String[] parts = s.split(",");
        if (parts.length != 4) return null;
        try {
            return new int[]{
                    Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim())
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
