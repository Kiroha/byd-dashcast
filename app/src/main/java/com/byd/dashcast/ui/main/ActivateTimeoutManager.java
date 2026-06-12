package com.byd.dashcast.ui.main;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.byd.dashcast.AppLogger;

/**
 * Manages the 30-second watchdog that fires if the cluster display never connects
 * after the user taps Activate. Owns its own Handler so it has no dependency on the
 * Activity's screenshot/state-poll handler.
 */
public final class ActivateTimeoutManager {

    private static final String TAG        = "ActivateTimeoutManager";
    private static final long   TIMEOUT_MS = 30_000;

    public interface Host {
        Context getContext();
        void onActivateTimeout();
    }

    private final Host    mHost;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable      mPending;

    public ActivateTimeoutManager(Host host) {
        mHost = host;
    }

    /** Starts (or restarts) the 30-second watchdog. */
    public void start() {
        cancel();
        mPending = () -> {
            mPending = null;
            AppLogger.w(TAG, "Activate cluster timeout (30 s)");
            mHost.onActivateTimeout();
        };
        mHandler.postDelayed(mPending, TIMEOUT_MS);
    }

    /** Cancels the watchdog if it is pending. Safe to call when nothing is pending. */
    public void cancel() {
        if (mPending != null) {
            mHandler.removeCallbacks(mPending);
            mPending = null;
        }
    }
}
