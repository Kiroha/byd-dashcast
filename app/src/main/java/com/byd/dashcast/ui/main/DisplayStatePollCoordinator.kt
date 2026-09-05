package com.byd.dashcast.ui.main

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.proxy.ShellGateway
import com.byd.dashcast.util.AppLogger

/**
 * Polls the cluster and main-display app processes every 5 s via `pidof` (uid 2000)
 * and notifies the Host when a tracked process disappears.
 *
 * Call [start] in onStart and [stop] in onStop.
 */
class DisplayStatePollCoordinator(private val mHost: Host) {

    interface Host {
        fun getContext(): Context
        fun isActivityAlive(): Boolean
        fun runOnMainThread(r: Runnable)
        /** Package currently tracked on the cluster display, or null. */
        fun getClusterPkg(): String?
        /** Milliseconds since the last app launch (grace period guard). */
        fun getLastLaunchTime(): Long
        /** Called on the main thread when the cluster app process has died. */
        fun onClusterPkgDied()
        /** Package tracked on the main display, or null. */
        fun getMainDisplayPkg(): String?
        /** Called on the main thread when the main-display app process has died. */
        fun onMainPkgDied()
    }

    private val mHandler = Handler(Looper.getMainLooper())
    private var mPollRunnable: Runnable? = null

    fun start() {
        if (mPollRunnable != null) return
        val r = object : Runnable {
            override fun run() {
                reconcileCluster()
                reconcileMain()
                mHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        mPollRunnable = r
        mHandler.postDelayed(r, POLL_INTERVAL_MS)
    }

    fun stop() {
        val r = mPollRunnable
        if (r != null) {
            mHandler.removeCallbacks(r)
            mPollRunnable = null
        }
    }

    // ── Cluster process watchdog ──────────────────────────────────────────────

    private fun reconcileCluster() {
        val clusterPkg = mHost.getClusterPkg() ?: return

        // Grace period of 8 s after a launch so the process has time to register in pidof.
        if (System.currentTimeMillis() - mHost.getLastLaunchTime() < 8_000) {
            return
        }

        // Steady-state "alive" results are intentionally NOT logged: at one poll
        // per 5 s they kept the AppLogger buffer perpetually dirty, which locked
        // LogActivity into its 500 ms refresh cadence. Only the death transition
        // (below, WARN) is an event worth recording.
        ShellGateway.execShellWithResult(mHost.getContext(), "pidof $clusterPkg",
            object : AdbLocalClient.Callback {
                override fun onSuccess(out: String?) {
                    val alive = out != null && out.trim().isNotEmpty()
                    if (alive) {
                        return
                    }
                    mHost.runOnMainThread(Runnable {
                        if (!mHost.isActivityAlive()) return@Runnable
                        if (clusterPkg != mHost.getClusterPkg()) return@Runnable
                        AppLogger.w(TAG, "$clusterPkg process died → clearing cluster state")
                        mHost.onClusterPkgDied()
                    })
                }

                override fun onError(err: String?) {
                    AppLogger.w(TAG, "pidof failed: $err")
                }
            })
    }

    // ── Main-display process watchdog ─────────────────────────────────────────

    private fun reconcileMain() {
        val mainPkg = mHost.getMainDisplayPkg() ?: return

        ShellGateway.execShellWithResult(mHost.getContext(), "pidof $mainPkg",
            object : AdbLocalClient.Callback {
                override fun onSuccess(out: String?) {
                    val alive = out != null && out.trim().isNotEmpty()
                    if (alive) return
                    mHost.runOnMainThread(Runnable {
                        if (!mHost.isActivityAlive()) return@Runnable
                        if (mainPkg != mHost.getMainDisplayPkg()) return@Runnable
                        AppLogger.w(TAG, "main-display app $mainPkg process died → clearing main marker")
                        mHost.onMainPkgDied()
                    })
                }

                override fun onError(err: String?) {
                    AppLogger.w(TAG, "main pidof failed: $err")
                }
            })
    }

    companion object {
        private const val TAG = "DisplayStatePoll"
        private const val POLL_INTERVAL_MS = 5_000L
    }
}
