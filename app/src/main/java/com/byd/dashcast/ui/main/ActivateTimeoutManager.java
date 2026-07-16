package com.byd.dashcast.ui.main;

import android.os.Handler;
import android.os.Looper;

import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.util.concurrent.LifecycleGate;

/**
 * Manages the 30-second watchdog that fires if the cluster display never connects
 * after the user taps Activate. Owns its own Handler so it has no dependency on the
 * Activity's screenshot/state-poll handler.
 */
public final class ActivateTimeoutManager {

    private static final String TAG        = "ActivateTimeoutManager";
    private static final long   TIMEOUT_MS = 30_000;

    public interface Host {
        void onActivateTimeout();
    }

    private final Host    mHost;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final LifecycleGate mLifecycleGate = new LifecycleGate();
    private Runnable      mPending;

    public ActivateTimeoutManager(Host host) {
        mHost = host;
    }

    /** Starts (or restarts) the 30-second watchdog. */
    public void start() {
        final LifecycleGate.Token operation = mLifecycleGate.capture();
        if (!operation.isValid()) return;
        cancel();
        mPending = () -> {
            mPending = null;
            if (!operation.isValid()) return;
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

    /** Permanently cancels callbacks owned by the Activity. Safe to call repeatedly. */
    public void destroy() {
        mLifecycleGate.invalidate();
        cancel();
        mHandler.removeCallbacksAndMessages(null);
    }
}
