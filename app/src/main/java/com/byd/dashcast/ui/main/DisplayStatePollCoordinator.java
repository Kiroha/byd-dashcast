package com.byd.dashcast.ui.main;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.byd.dashcast.AppLogger;
import com.byd.dashcast.data.prefs.ClusterPrefs;
import com.byd.dashcast.proxy.ShellGateway;
import com.byd.dashcast.AdbLocalClient;

/**
 * Polls the cluster and main-display app processes every 5 s via {@code pidof} (uid 2000)
 * and notifies the Host when a tracked process disappears.
 *
 * Call {@link #start()} in onStart and {@link #stop()} in onStop.
 */
public final class DisplayStatePollCoordinator {

    private static final String TAG = "DisplayStatePoll";
    private static final long   POLL_INTERVAL_MS = 5_000;

    public interface Host {
        Context getContext();
        boolean isActivityAlive();
        void runOnMainThread(Runnable r);
        /** Package currently tracked on the cluster display, or null. */
        String getClusterPkg();
        /** Milliseconds since the last app launch (grace period guard). */
        long getLastLaunchTime();
        /** Called on the main thread when the cluster app process has died. */
        void onClusterPkgDied();
        /** Package tracked on the main display, or null. */
        String getMainDisplayPkg();
        /** Called on the main thread when the main-display app process has died. */
        void onMainPkgDied();
    }

    private final Host    mHost;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable      mPollRunnable;

    public DisplayStatePollCoordinator(Host host) {
        mHost = host;
    }

    public void start() {
        if (mPollRunnable != null) return;
        mPollRunnable = new Runnable() {
            @Override public void run() {
                reconcileCluster();
                reconcileMain();
                mHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        };
        mHandler.postDelayed(mPollRunnable, POLL_INTERVAL_MS);
    }

    public void stop() {
        if (mPollRunnable != null) {
            mHandler.removeCallbacks(mPollRunnable);
            mPollRunnable = null;
        }
    }

    // ── Cluster process watchdog ──────────────────────────────────────────────

    private void reconcileCluster() {
        final String clusterPkg = mHost.getClusterPkg();
        if (clusterPkg == null) return;

        // Grace period of 8 s after a launch so the process has time to register in pidof.
        if (System.currentTimeMillis() - mHost.getLastLaunchTime() < 8_000) {
            AppLogger.d(TAG, "skipping pidof during launch grace period for " + clusterPkg);
            return;
        }

        ShellGateway.execShellWithResult(mHost.getContext(), "pidof " + clusterPkg,
                new AdbLocalClient.Callback() {
                    @Override public void onSuccess(String output) {
                        boolean alive = output != null && !output.trim().isEmpty();
                        if (alive) {
                            AppLogger.d(TAG, clusterPkg + " alive (pid " + output.trim() + ")");
                            return;
                        }
                        mHost.runOnMainThread(new Runnable() {
                            @Override public void run() {
                                if (!mHost.isActivityAlive()) return;
                                if (!clusterPkg.equals(mHost.getClusterPkg())) return;
                                AppLogger.w(TAG, clusterPkg + " process died → clearing cluster state");
                                mHost.onClusterPkgDied();
                            }
                        });
                    }
                    @Override public void onError(String error) {
                        AppLogger.w(TAG, "pidof failed: " + error);
                    }
                });
    }

    // ── Main-display process watchdog ─────────────────────────────────────────

    private void reconcileMain() {
        final String mainPkg = mHost.getMainDisplayPkg();
        if (mainPkg == null) return;

        ShellGateway.execShellWithResult(mHost.getContext(), "pidof " + mainPkg,
                new AdbLocalClient.Callback() {
                    @Override public void onSuccess(String output) {
                        boolean alive = output != null && !output.trim().isEmpty();
                        if (alive) return;
                        mHost.runOnMainThread(new Runnable() {
                            @Override public void run() {
                                if (!mHost.isActivityAlive()) return;
                                if (!mainPkg.equals(mHost.getMainDisplayPkg())) return;
                                AppLogger.w(TAG, "main-display app " + mainPkg
                                        + " process died → clearing main marker");
                                mHost.onMainPkgDied();
                            }
                        });
                    }
                    @Override public void onError(String error) {
                        AppLogger.w(TAG, "main pidof failed: " + error);
                    }
                });
    }
}
