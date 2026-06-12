package com.byd.dashcast;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.byd.dashcast.data.prefs.ClusterPrefs;

/**
 * Owns the set of packages launched on the cluster display during this session.
 *
 * Provides add / remove / contains, and the full eviction pipeline
 * (move → display 0 + force-stop) used by restoreBydDashboard / originCluster.
 *
 * The set is persisted after every mutation so BootDisplayCleanup can recover
 * after a process death.
 */
public final class ClusterSessionTracker {

    private static final String TAG = "ClusterSessionTracker";

    private final Context mAppCtx;
    private final java.util.Set<String> mPkgs = new java.util.LinkedHashSet<>();

    public ClusterSessionTracker(Context context) {
        mAppCtx = context.getApplicationContext();
    }

    // ── Set mutations ─────────────────────────────────────────────────────────

    public void add(String pkg) {
        if (pkg == null) return;
        mPkgs.add(pkg);
        persist();
    }

    public void remove(String pkg) {
        if (pkg == null) return;
        mPkgs.remove(pkg);
        persist();
    }

    public boolean contains(String pkg) {
        return pkg != null && mPkgs.contains(pkg);
    }

    // ── Bulk operations ───────────────────────────────────────────────────────

    /**
     * Moves all tracked packages back to Display 0 via ClusterService and clears the set.
     * If the service is null the set is preserved so BootDisplayCleanup can retry at boot.
     */
    public void moveToMainDisplay(ClusterService svc) {
        if (mPkgs.isEmpty()) return;
        if (svc == null) {
            AppLogger.w(TAG, "moveToMainDisplay: service not bound — preserving set for boot cleanup");
            return;
        }
        AppLogger.i(TAG, "moveToMainDisplay: " + mPkgs.size() + " apps → " + mPkgs);
        for (String pkg : mPkgs) svc.moveTaskToDisplay(pkg, 0, null);
        mPkgs.clear();
        persist();
    }

    /**
     * Builds a deduplicated list from {@code main} + {@code second}, then sequentially
     * moves each to Display 0 and force-stops it. Runs {@code onAllDone} on the main
     * thread once every package has been processed (success or error).
     */
    public void evictAllThen(ClusterService svc, String main, String second, Runnable onAllDone) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (main   != null && !main.isEmpty())   set.add(main);
        if (second != null && !second.isEmpty()) set.add(second);
        java.util.List<String> pkgs = new java.util.ArrayList<>(set);

        if (pkgs.isEmpty()) { onAllDone.run(); return; }

        if (svc == null) {
            for (String p : pkgs) AdbLocalClient.forceStopApp(mAppCtx, p, null);
            new Handler(Looper.getMainLooper()).postDelayed(onAllDone, 800L);
            return;
        }
        evictNext(svc, pkgs, 0, onAllDone);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void evictNext(ClusterService svc, java.util.List<String> pkgs,
                           int idx, Runnable onAllDone) {
        if (idx >= pkgs.size()) {
            new Handler(Looper.getMainLooper()).post(onAllDone);
            return;
        }
        final String pkg = pkgs.get(idx);
        if (pkg == null || pkg.isEmpty()) { evictNext(svc, pkgs, idx + 1, onAllDone); return; }

        AppLogger.i(TAG, "evict: move→display0 " + pkg);
        svc.moveTaskToDisplay(pkg, 0, new ClusterService.LaunchCallback() {
            @Override public void onResult(boolean ok) {
                AppLogger.i(TAG, "evict: move " + pkg + " → " + (ok ? "OK" : "KO") + " — force-stop");
                remove(pkg);
                AdbLocalClient.forceStopApp(mAppCtx, pkg, new AdbLocalClient.Callback() {
                    @Override public void onSuccess(String r) { evictNext(svc, pkgs, idx + 1, onAllDone); }
                    @Override public void onError(String e) {
                        AppLogger.w(TAG, "evict: forceStop " + pkg + " ERR: " + e);
                        evictNext(svc, pkgs, idx + 1, onAllDone);
                    }
                });
            }
        });
    }

    private void persist() {
        ClusterPrefs.setSessionClusterPkgs(mAppCtx, new java.util.HashSet<>(mPkgs));
    }
}
