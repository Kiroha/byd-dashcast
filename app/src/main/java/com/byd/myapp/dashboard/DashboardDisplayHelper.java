package com.byd.myapp.dashboard;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import com.byd.myapp.AdbLocalClient;
import com.byd.myapp.AppLogger;

/**
 * DashboardDisplayHelper — detects the secondary display (instrument cluster).
 *
 * BEHAVIOR ON BYD SEAL:
 *   The cluster is NOT exposed as a native visible Android display.
 *   ClusterManager.activateClusterDisplay() (sendInfo 1000/30+16) must be called first
 *   so that AutoDisplayService creates its VirtualDisplay; then we listen for it.
 *
 *   This helper delegates all logic to ClusterManager.activateClusterDisplay(),
 *   which handles: sendInfo + polling VirtualDisplay + timeout.
 *
 *   The DisplayListener remains registered to detect disconnections.
 */
public class DashboardDisplayHelper {

    private static final String TAG = "DashboardDisplayHelper";

    public interface Listener {
        void onDashboardDisplayConnected(Display display, int displayId);
        void onDashboardDisplayDisconnected();
    }

    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private final Listener mListener;
    private final ClusterManager mClusterManager;

    // Known cluster display ID — -1 if not connected
    private int mKnownClusterDisplayId = -1;

    private final DisplayManager.DisplayListener mDisconnectListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {}

                @Override
                public void onDisplayRemoved(int displayId) {
                    if (displayId != mKnownClusterDisplayId) return;
                    AppLogger.i(TAG, "Dashboard display removed: id=" + displayId);
                    mKnownClusterDisplayId = -1;
                    mListener.onDashboardDisplayDisconnected();
                }

                @Override
                public void onDisplayChanged(int displayId) {}
            };

    public DashboardDisplayHelper(Context context, Listener listener) {
        mContext = context.getApplicationContext();
        mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        mListener = listener;
        mClusterManager = new ClusterManager(context);
    }

    /**
     * Triggers the cluster activation sequence.
     * @param freedomJustStarted  true if ClusterService just launched Freedom via startFreedom().
     *                            Avoids a redundant second call to startFreedom() in ClusterManager
     *                            if the VirtualDisplay has not yet appeared within the 2s delay.
     */
    public void start(boolean freedomJustStarted) {
        mKnownClusterDisplayId = -1;
        mDisplayManager.registerDisplayListener(mDisconnectListener, null);
        mClusterManager.activateClusterDisplay(freedomJustStarted, new ClusterManager.DisplayReadyCallback() {
            @Override
            public void onDisplayReady(Display display, int displayId) {
                // Guard: if stop() was already called, discard this callback
                if (mKnownClusterDisplayId == -2) return;
                mKnownClusterDisplayId = displayId;
                AppLogger.i(TAG, "Dashboard display ready: id=" + displayId
                        + " name=" + (display != null ? display.getName() : "null"));
                mListener.onDashboardDisplayConnected(display, displayId);
            }

            @Override
            public void onDisplayTimeout() {
                // Guard: if stop() was already called, discard orphan callback
                if (mKnownClusterDisplayId == -2) {
                    AppLogger.d(TAG, "onDisplayTimeout ignored — stop() already called");
                    return;
                }
                // VirtualDisplay not created after the full sequence (30→6s→16→6s→35).
                // No fallback to hardcoded displayId=1 — report failure to the caller.
                AppLogger.e(TAG, "Dashboard VirtualDisplay timeout — activation sequence failed");
                mKnownClusterDisplayId = -1;
                mListener.onDashboardDisplayDisconnected();
            }
        });
    }

    /** No-arg overload — Freedom not started by the caller (default behavior). */
    public void start() {
        start(false);
    }

    public void stop() {
        // Sentinel: any orphan ClusterManager callback (handler postDelayed) will be ignored
        mKnownClusterDisplayId = -2;

        // Cancel ALL pending Handler callbacks (polls + timeout) in ClusterManager.
        // Without this, the 3s timeout fires after stop() and calls onDashboardDisplayConnected(null,1) → NPE.
        mClusterManager.cancel();

        mDisplayManager.unregisterDisplayListener(mDisconnectListener);

        // DO NOT call stopService(AutoDisplayService):
        // That system service is started at BOOT and manages the cluster VirtualDisplay.
        // Stopping it would destroy the PRESENTATION VirtualDisplay for the entire Android session.

        // Close projection mode via sendInfo(1000, 18) = stop projection + sendInfo(1000, 0) = refresh Qt.
        // Sent via ADB relay (uid=2000) because com.byd.myapp is blocked by Binder SecurityException.
        // Chained: cmd=18 first, then cmd=0 in the callback to guarantee execution order.
        AdbLocalClient.sendInfo(mContext, ClusterManager.CLUSTER_TYPE, ClusterManager.CMD_STOP_PROJECTION, "",
            new AdbLocalClient.Callback() {
                @Override public void onSuccess(String out) {
                    AppLogger.i(TAG, "stopProjection ADB(cmd=18): " + out);
                    AdbLocalClient.sendInfo(mContext, ClusterManager.CLUSTER_TYPE, ClusterManager.CMD_RESTORE_NATIVE, "",
                        new AdbLocalClient.Callback() {
                            @Override public void onSuccess(String o) { AppLogger.i(TAG, "restoreNative ADB(cmd=0): " + o); }
                            @Override public void onError(String e) { AppLogger.e(TAG, "restoreNative ADB error: " + e); }
                        });
                }
                @Override public void onError(String err) {
                    AppLogger.e(TAG, "stopProjection ADB error: " + err);
                }
            });        // Reset to -1 happens in start() — NOT here.
        // Keep sentinel -2 until next start() to block orphan ADB callbacks
        // (background thread may post on mHandler after cancel()).
    }

    /**
     * Variant of stop() without sending ADB restore commands.
     * Use when restoration has already been sent upstream (e.g. restoreBydDashboard)
     * to avoid double-sending sendInfo(18+0).
     */
    public void stopWithoutAdb() {
        mKnownClusterDisplayId = -2;
        mClusterManager.cancel();
        mDisplayManager.unregisterDisplayListener(mDisconnectListener);
        AppLogger.i(TAG, "stopWithoutAdb — ADB already sent upstream");
    }

    public int getKnownClusterDisplayId() {
        return mKnownClusterDisplayId;
    }
}
