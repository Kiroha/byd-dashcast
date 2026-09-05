package com.byd.dashcast.ui.main

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast

import com.byd.dashcast.R
import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.util.concurrent.GenerationGate

/**
 * Manages split-screen state on the cluster display.
 *
 * Owns: `mCurrentSplitSlot`, `mSecondDashboardPkg/App`. Extracted from MainActivity to keep
 * split bookkeeping out of the god-class.
 *
 * MainActivity delegates [showSplitMenu], [applySplitSlot] and [clearSplitState] to this
 * controller. The second-app insertion logic inside `onSendToDashboard` is handled inline by
 * MainActivity (requires updating `mLastLaunchTime` for the state-poll grace period).
 *
 * Kotlin port notes:
 *  - The getters stay FUNCTIONS rather than becoming properties. Every production call site in
 *    MainActivity.kt uses method syntax (isInSplitMode(), getCurrentSplitSlot(),
 *    getSecondDashboardPkg()...), and a Kotlin property would make those forms illegal. Keeping
 *    functions leaves the code that runs in a car untouched.
 *  - The field name mCurrentSplitSlot is a CONTRACT: SplitReplacementStateTest reads it with
 *    SplitController::class.java.getDeclaredField("mCurrentSplitSlot"). Renaming it would break
 *    that test at runtime, not at compile time.
 */
class SplitController(private val mHost: Host) {

    interface Host {
        // Nullability here is not a free choice: MainActivity.kt implements this interface
        // directly (class MainActivity : ... SplitController.Host) and the unit test's
        // RecordingHost implements it too. Both already declare these exact nullabilities.
        fun getContext(): Context
        fun getClusterServiceIfBound(): ClusterService?
        fun getCurrentDashboardPkg(): String?
        fun getCurrentDashboardApp(): String?
        fun setCurrentDashboardPkg(pkg: String?)
        fun setCurrentDashboardApp(app: String?)
        /** Called after split state changes so the UI (label + split button) can be refreshed. */
        fun onSplitStateChanged()
        // Named 'runnable', matching RecordingHost. MainActivity satisfies this by INHERITING
        // Activity.runOnUiThread, so no Kotlin override there can clash with the name.
        fun runOnUiThread(runnable: Runnable)
        fun isActivityAlive(): Boolean
    }

    private var mCurrentSplitSlot = 0      // 0=full, 1=left, 2=right
    private var mSecondDashboardPkg: String? = null
    private var mSecondDashboardApp: String? = null
    private val mReplacementGate = GenerationGate()

    // ── Getters ───────────────────────────────────────────────────────────────

    fun getCurrentSplitSlot(): Int = mCurrentSplitSlot
    fun getSecondDashboardPkg(): String? = mSecondDashboardPkg
    fun getSecondDashboardApp(): String? = mSecondDashboardApp
    fun isInSplitMode(): Boolean = mCurrentSplitSlot != 0

    // ── Setters (for use by MainActivity when the host updates external state) ─

    fun setSecondDashboardPkg(pkg: String?) { mSecondDashboardPkg = pkg }
    fun setSecondDashboardApp(app: String?) { mSecondDashboardApp = app }

    /** Starts a user replacement intent and invalidates every older stop/launch completion. */
    fun beginSecondDashboardReplacement(): Int {
        mReplacementGate.invalidate()
        return mReplacementGate.capture()
    }

    fun isCurrentSecondDashboardReplacement(generation: Int): Boolean =
            mReplacementGate.isCurrent(generation)

    /** Clears a verified-stopped occupant only for the latest replacement intent. */
    fun clearSecondDashboardIfMatches(expectedPkg: String?, generation: Int): Boolean {
        if (!mReplacementGate.isCurrent(generation)
                || expectedPkg == null || expectedPkg != mSecondDashboardPkg) return false
        mSecondDashboardApp = null
        mSecondDashboardPkg = null
        mHost.onSplitStateChanged()
        return true
    }

    /** Atomically commits full-screen state after the secondary occupant was verified stopped. */
    fun commitFullScreenIfMatches(expectedPkg: String?, generation: Int): Boolean {
        if (!mReplacementGate.isCurrent(generation)
                || expectedPkg == null || expectedPkg != mSecondDashboardPkg) return false
        mSecondDashboardApp = null
        mSecondDashboardPkg = null
        mCurrentSplitSlot = 0
        mHost.onSplitStateChanged()
        return true
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Shows a popup to choose full-screen / left-50% / right-50% for the current cluster app. */
    fun showSplitMenu(anchor: View) {
        val svc = mHost.getClusterServiceIfBound()
        val curPkg = mHost.getCurrentDashboardPkg()
        if (svc == null || curPkg == null) {
            AppLogger.w(TAG, "showSplitMenu ignored — svc=" + svc + " pkg=" + curPkg)
            Toast.makeText(mHost.getContext(),
                    mHost.getContext().getString(R.string.toast_no_app_cluster),
                    Toast.LENGTH_SHORT).show()
            return
        }
        AppLogger.d(TAG, "showSplitMenu — app=" + curPkg
                + " slot=" + mCurrentSplitSlot + " second=" + mSecondDashboardPkg)
        val dims = getClusterDimensions(svc)
        val w = dims[0]
        val h = dims[1]
        val popup = PopupMenu(mHost.getContext(), anchor)
        popup.menu.add(0, 1, 0, mHost.getContext().getString(R.string.split_full_screen))
        popup.menu.add(0, 2, 0, mHost.getContext().getString(R.string.split_left))
        popup.menu.add(0, 3, 0, mHost.getContext().getString(R.string.split_right))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> applySplitSlot(0, 0, 0, w, h)
                2 -> applySplitSlot(1, 0, 0, w / 2, h)
                3 -> applySplitSlot(2, w / 2, 0, w, h)
            }
            true
        }
        popup.show()
    }

    /**
     * Resizes the main cluster app to the given slot bounds.
     * slot 0 = full screen, 1 = left 50%, 2 = right 50%.
     */
    fun applySplitSlot(slot: Int, l: Int, t: Int, r: Int, b: Int) {
        val svc = mHost.getClusterServiceIfBound()
        if (svc == null) {
            AppLogger.w(TAG, "applySplitSlot: service not bound — ignored")
            return
        }
        val splitPkg = mHost.getCurrentDashboardPkg()
        val splitApp = mHost.getCurrentDashboardApp()
        AppLogger.i(TAG, "applySplitSlot slot=" + slot
                + " bounds=[" + l + "," + t + "," + r + "," + b + "]"
                + " pkg=" + splitPkg + " second=" + mSecondDashboardPkg)

        val generation = beginSecondDashboardReplacement()
        if (slot == 0 && mSecondDashboardPkg != null) {
            // Local copy, named exactly as in the Java: SplitReplacementStateTest asserts on
            // the literal text "commitFullScreenIfMatches(secondPkg, generation)".
            val secondPkg = mSecondDashboardPkg
            AppLogger.i(TAG, "split → full screen: force-stop second=" + secondPkg)
            AdbLocalClient.forceStopApp(mHost.getContext(), secondPkg,
                    object : AdbLocalClient.Callback {
                // String? deliberately: AdbLocalClient.Callback is still unannotated Java, so
                // either nullability compiles today, but it will declare String? when that class
                // is ported last — and a non-null override would then be an illegal narrowing.
                override fun onSuccess(report: String?) {
                    mHost.runOnUiThread {
                        if (!mHost.isActivityAlive()) return@runOnUiThread
                        if (!commitFullScreenIfMatches(secondPkg, generation)) return@runOnUiThread
                        relaunchPrimaryInSlot(svc, splitPkg, splitApp, l, t, r, b, slot, generation)
                    }
                }

                override fun onError(error: String?) {
                    mHost.runOnUiThread {
                        if (!mHost.isActivityAlive()) return@runOnUiThread
                        if (!isCurrentSecondDashboardReplacement(generation)) return@runOnUiThread
                        AppLogger.e(TAG, "split → full screen: secondary stop failed: " + error)
                        Toast.makeText(mHost.getContext(),
                                mHost.getContext().getString(R.string.toast_kill_failed, error),
                                Toast.LENGTH_LONG).show()
                    }
                }
            })
            return
        }
        mCurrentSplitSlot = slot
        relaunchPrimaryInSlot(svc, splitPkg, splitApp, l, t, r, b, slot, generation)
    }

    private fun relaunchPrimaryInSlot(svc: ClusterService, splitPkg: String?, splitApp: String?,
                                      l: Int, t: Int, r: Int, b: Int, slot: Int, generation: Int) {
        if (!mHost.isActivityAlive()) return
        AdbLocalClient.forceStopApp(mHost.getContext(), splitPkg, object : AdbLocalClient.Callback {
            override fun onSuccess(ignored: String?) {
                if (mHost.isActivityAlive() && isCurrentSecondDashboardReplacement(generation)) {
                    launchInSlot(svc, splitPkg, splitApp, l, t, r, b, slot, generation)
                }
            }
            override fun onError(error: String?) {
                // force-stop failed: attempt relaunch anyway
                if (mHost.isActivityAlive() && isCurrentSecondDashboardReplacement(generation)) {
                    launchInSlot(svc, splitPkg, splitApp, l, t, r, b, slot, generation)
                }
            }
        })
    }

    private fun launchInSlot(svc: ClusterService, splitPkg: String?, splitApp: String?,
                             l: Int, t: Int, r: Int, b: Int, slot: Int, generation: Int) {
        svc.launchOnDashboardWithBounds(splitPkg, l, t, r, b) { launched ->
            mHost.runOnUiThread {
                if (!mHost.isActivityAlive()) return@runOnUiThread
                if (!isCurrentSecondDashboardReplacement(generation)) return@runOnUiThread
                if (launched) {
                    mHost.setCurrentDashboardPkg(splitPkg)
                    mHost.setCurrentDashboardApp(splitApp)
                    AppLogger.i(TAG, "split slot " + slot + " OK ["
                            + l + "," + t + "," + r + "," + b + "]")
                    mHost.onSplitStateChanged()
                } else {
                    AppLogger.e(TAG, "split relaunch FAILED slot=" + slot)
                    Toast.makeText(mHost.getContext(),
                            mHost.getContext().getString(
                                    R.string.toast_app_launch_failed, splitApp),
                            Toast.LENGTH_SHORT).show()
                    mCurrentSplitSlot = 0
                    mHost.onSplitStateChanged()
                }
            }
        }
    }

    /** Resets split state (slot + second app). Call when the main app changes or cluster stops. */
    fun clearSplitState() {
        mReplacementGate.invalidate()
        if (mCurrentSplitSlot != 0 || mSecondDashboardPkg != null) {
            AppLogger.d(TAG, "clearSplitState — slot=" + mCurrentSplitSlot
                    + " second=" + mSecondDashboardPkg)
        }
        mSecondDashboardApp = null
        mSecondDashboardPkg = null
        mCurrentSplitSlot = 0
        // Use direct call when already on main thread so callers see the updated isInSplitMode()
        // immediately; post only when invoked from a bg thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mHost.onSplitStateChanged()
        } else {
            mHost.runOnUiThread {
                if (mHost.isActivityAlive()) mHost.onSplitStateChanged()
            }
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    fun getClusterDimensions(): IntArray {
        val svc = mHost.getClusterServiceIfBound() ?: return intArrayOf(1920, 720)
        return getClusterDimensions(svc)
    }

    private fun getClusterDimensions(svc: ClusterService): IntArray {
        val w = svc.mirrorManager?.getClusterWidth() ?: 0
        val h = svc.mirrorManager?.getClusterHeight() ?: 0
        if (w > 0 && h > 0) return intArrayOf(w, h)
        AppLogger.w(TAG, "getClusterDimensions → fallback 1920×720")
        return intArrayOf(1920, 720)
    }

    companion object {
        private const val TAG = "SplitController"
    }
}
