package com.byd.dashcast.cluster

import android.content.Context
import android.os.Handler
import android.os.Looper

import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.util.AppLogger

/**
 * Owns the set of packages launched on the cluster display during this session.
 *
 * Provides add / remove / contains, and the full eviction pipeline
 * (move → display 0 + force-stop) used by restoreBydDashboard / originCluster.
 *
 * The set is persisted after every mutation so BootDisplayCleanup can recover
 * after a process death.
 */
class ClusterSessionTracker(context: Context) {

    private val mAppCtx: Context = context.applicationContext
    private val mPkgs: MutableSet<String> = LinkedHashSet()

    // ── Set mutations ─────────────────────────────────────────────────────────

    fun add(pkg: String?) {
        if (pkg == null) return
        mPkgs.add(pkg)
        persist()
    }

    fun remove(pkg: String?) {
        if (pkg == null) return
        mPkgs.remove(pkg)
        persist()
    }

    fun contains(pkg: String?): Boolean = pkg != null && mPkgs.contains(pkg)

    // ── Bulk operations ───────────────────────────────────────────────────────

    /**
     * Moves all tracked packages back to Display 0 via ClusterService and clears the set.
     * If the service is null the set is preserved so BootDisplayCleanup can retry at boot.
     */
    fun moveToMainDisplay(svc: ClusterService?) {
        if (mPkgs.isEmpty()) return
        if (svc == null) {
            AppLogger.w(TAG, "moveToMainDisplay: service not bound — preserving set for boot cleanup")
            return
        }
        AppLogger.i(TAG, "moveToMainDisplay: " + mPkgs.size + " apps → " + mPkgs)
        for (pkg in mPkgs) svc.moveTaskToDisplay(pkg, 0, null)
        mPkgs.clear()
        persist()
    }

    /**
     * Builds a deduplicated list from [main] + [second], then sequentially moves each to
     * Display 0 and force-stops it. Runs [onAllDone] on the main thread once every package
     * has been processed (success or error).
     */
    fun evictAllThen(svc: ClusterService?, main: String?, second: String?, onAllDone: Runnable) {
        val set = LinkedHashSet<String>()
        if (!main.isNullOrEmpty()) set.add(main)
        if (!second.isNullOrEmpty()) set.add(second)
        val pkgs = ArrayList(set)

        if (pkgs.isEmpty()) {
            onAllDone.run()
            return
        }

        if (svc == null) {
            for (p in pkgs) AdbLocalClient.forceStopApp(mAppCtx, p, null)
            Handler(Looper.getMainLooper()).postDelayed(onAllDone, 800L)
            return
        }
        evictNext(svc, pkgs, 0, onAllDone)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun evictNext(svc: ClusterService, pkgs: List<String>, idx: Int, onAllDone: Runnable) {
        if (idx >= pkgs.size) {
            Handler(Looper.getMainLooper()).post(onAllDone)
            return
        }
        val pkg = pkgs[idx]
        if (pkg.isEmpty()) {
            evictNext(svc, pkgs, idx + 1, onAllDone)
            return
        }

        AppLogger.i(TAG, "evict: move→display0 $pkg")
        svc.moveTaskToDisplay(pkg, 0, object : ClusterService.LaunchCallback {
            override fun onResult(ok: Boolean) {
                AppLogger.i(TAG, "evict: move $pkg → " + (if (ok) "OK" else "KO") + " — force-stop")
                remove(pkg)
                AdbLocalClient.forceStopApp(mAppCtx, pkg, object : AdbLocalClient.Callback {
                    override fun onSuccess(r: String?) {
                        evictNext(svc, pkgs, idx + 1, onAllDone)
                    }

                    override fun onError(e: String?) {
                        AppLogger.w(TAG, "evict: forceStop $pkg ERR: $e")
                        evictNext(svc, pkgs, idx + 1, onAllDone)
                    }
                })
            }
        })
    }

    private fun persist() {
        ClusterPrefs.setSessionClusterPkgs(mAppCtx, HashSet(mPkgs))
    }

    companion object {
        private const val TAG = "ClusterSessionTracker"
    }
}
