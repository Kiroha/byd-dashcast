package com.byd.dashcast.ui.main

import android.os.Handler
import android.os.Looper

import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.util.concurrent.LifecycleGate

/**
 * Manages the 30-second watchdog that fires if the cluster display never connects
 * after the user taps Activate. Owns its own Handler so it has no dependency on the
 * Activity's screenshot/state-poll handler.
 */
class ActivateTimeoutManager(private val mHost: Host) {

    interface Host {
        fun onActivateTimeout()
    }

    private val mHandler = Handler(Looper.getMainLooper())
    private val mLifecycleGate = LifecycleGate()
    private var mPending: Runnable? = null

    /** Starts (or restarts) the 30-second watchdog. */
    fun start() {
        val operation = mLifecycleGate.capture()
        if (!operation.isValid) return
        cancel()
        val pending = Runnable {
            mPending = null
            if (!operation.isValid) return@Runnable
            AppLogger.w(TAG, "Activate cluster timeout (30 s)")
            mHost.onActivateTimeout()
        }
        mPending = pending
        mHandler.postDelayed(pending, TIMEOUT_MS)
    }

    /** Cancels the watchdog if it is pending. Safe to call when nothing is pending. */
    fun cancel() {
        mPending?.let {
            mHandler.removeCallbacks(it)
            mPending = null
        }
    }

    /** Permanently cancels callbacks owned by the Activity. Safe to call repeatedly. */
    fun destroy() {
        mLifecycleGate.invalidate()
        cancel()
        mHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val TAG = "ActivateTimeoutManager"
        private const val TIMEOUT_MS = 30_000L
    }
}
