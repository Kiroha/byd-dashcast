package com.byd.dashcast.cluster.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.util.AppLogger

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
class DashboardDisplayHelper(context: Context, private val mListener: Listener) {

    interface Listener {
        fun onDashboardDisplayConnected(display: Display?, displayId: Int)
        fun onDashboardDisplayDisconnected()
    }

    private val mContext: Context = context.applicationContext
    private val mDisplayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val mClusterManager = ClusterManager(context)

    // Known cluster display ID — -1 if not connected
    private var mKnownClusterDisplayId = -1

    private val mDisconnectListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId != mKnownClusterDisplayId) return
            AppLogger.i(TAG, "Dashboard display removed: id=$displayId")
            mKnownClusterDisplayId = -1
            mListener.onDashboardDisplayDisconnected()
        }

        override fun onDisplayChanged(displayId: Int) {}
    }

    /** Triggers the cluster activation sequence. */
    fun start() {
        mKnownClusterDisplayId = -1
        mDisplayManager.registerDisplayListener(mDisconnectListener, null)
        mClusterManager.activateClusterDisplay(object : ClusterManager.DisplayReadyCallback {
            override fun onDisplayReady(display: Display?, displayId: Int) {
                // Guard: if stop() was already called, discard this callback
                if (mKnownClusterDisplayId == -2) return
                mKnownClusterDisplayId = displayId
                AppLogger.i(TAG, "Dashboard display ready: id=$displayId name=" + (display?.name ?: "null"))
                mListener.onDashboardDisplayConnected(display, displayId)
            }

            override fun onDisplayTimeout() {
                // Guard: if stop() was already called, discard orphan callback
                if (mKnownClusterDisplayId == -2) {
                    AppLogger.d(TAG, "onDisplayTimeout ignored — stop() already called")
                    return
                }
                // VirtualDisplay not created after the full sequence (30→6s→16→6s→35).
                // No fallback to hardcoded displayId=1 — report failure to the caller.
                // ClusterManager keeps watching via armLateArrivalWatch(); if the VD appears
                // within the grace period, onDisplayLateReady fires below.
                AppLogger.e(TAG, "Dashboard VirtualDisplay timeout — activation sequence failed")
                mKnownClusterDisplayId = -1
                mListener.onDashboardDisplayDisconnected()
            }

            override fun onDisplayLateReady(display: Display?, displayId: Int) {
                // Ignore if stop() was already called (sentinel -2).
                if (mKnownClusterDisplayId == -2) {
                    AppLogger.d(TAG, "onDisplayLateReady: stop() called — ignoring")
                    return
                }
                // Ignore if the normal path already succeeded (shouldn't happen but guard).
                if (mKnownClusterDisplayId > 0) {
                    AppLogger.d(TAG, "onDisplayLateReady: already connected (id=$mKnownClusterDisplayId) — ignoring")
                    return
                }
                mKnownClusterDisplayId = displayId
                AppLogger.i(TAG, "Dashboard display late arrival: id=$displayId name=" + (display?.name ?: "null"))
                mListener.onDashboardDisplayConnected(display, displayId)
            }
        })
    }

    fun stop() {
        // Sentinel: any orphan ClusterManager callback (handler postDelayed) will be ignored
        mKnownClusterDisplayId = -2

        // Cancel ALL pending Handler callbacks (polls + timeout) in ClusterManager.
        // Without this, the 3s timeout fires after stop() and calls onDashboardDisplayConnected(null,1) → NPE.
        mClusterManager.cancel()

        mDisplayManager.unregisterDisplayListener(mDisconnectListener)

        // DO NOT call stopService(AutoDisplayService):
        // That system service is started at BOOT and manages the cluster VirtualDisplay.
        // Stopping it would destroy the PRESENTATION VirtualDisplay for the entire Android session.

        // Close projection mode via sendInfo(1000, 18) = stop projection + sendInfo(1000, 0) = refresh Qt.
        // Routed through AdbLocalClient.sendInfo (uid=2000) because com.byd.dashcast is blocked by a
        // Binder SecurityException when calling AutoContainer directly. AdbLocalClient.sendInfo goes
        // through the proxy daemon's typed transact first (ProxyClient.autoContainerSendInfo) and only
        // falls back to the ADB shell relay when the daemon is unavailable (legacy mode / DL5).
        // Chained: cmd=18 first, then cmd=0 in the callback to guarantee execution order.
        AdbLocalClient.sendInfo(
            mContext, ClusterManager.CLUSTER_TYPE, ClusterManager.CMD_STOP_PROJECTION, "",
            object : AdbLocalClient.Callback {
                override fun onSuccess(out: String?) {
                    AppLogger.i(TAG, "stopProjection ADB(cmd=18): $out")
                    AdbLocalClient.sendInfo(
                        mContext, ClusterManager.CLUSTER_TYPE, ClusterManager.CMD_RESTORE_NATIVE, "",
                        object : AdbLocalClient.Callback {
                            override fun onSuccess(o: String?) { AppLogger.i(TAG, "restoreNative ADB(cmd=0): $o") }
                            override fun onError(e: String?) { AppLogger.e(TAG, "restoreNative ADB error: $e") }
                        })
                }

                override fun onError(err: String?) {
                    AppLogger.e(TAG, "stopProjection ADB error: $err")
                }
            })
        // Reset to -1 happens in start() — NOT here.
        // Keep sentinel -2 until next start() to block orphan ADB callbacks
        // (background thread may post on mHandler after cancel()).
    }

    /**
     * Variant of stop() without sending ADB restore commands.
     * Use when restoration has already been sent upstream (e.g. restoreBydDashboard)
     * to avoid double-sending sendInfo(18+0).
     */
    fun stopWithoutAdb() {
        mKnownClusterDisplayId = -2
        mClusterManager.cancel()
        mDisplayManager.unregisterDisplayListener(mDisconnectListener)
        AppLogger.i(TAG, "stopWithoutAdb — ADB already sent upstream")
    }

    fun getKnownClusterDisplayId(): Int {
        // -2 is an internal sentinel (stop() already called); expose as -1 to callers so
        // they treat it as "not connected" rather than a raw magic value.
        return if (mKnownClusterDisplayId == -2) -1 else mKnownClusterDisplayId
    }

    companion object {
        private const val TAG = "DashboardDisplayHelper"
    }
}
