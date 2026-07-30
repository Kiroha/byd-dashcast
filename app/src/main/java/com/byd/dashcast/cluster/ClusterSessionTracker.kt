package com.byd.dashcast.cluster

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.infrastructure.task.TaskLocation
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.util.AppLogger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
        // One clock for the whole eviction — see ClusterEvictionPolicy.LANDING_BUDGET_MS.
        evictNext(svc, pkgs, 0, SystemClock.elapsedRealtime(), onAllDone)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun evictNext(
        svc: ClusterService,
        pkgs: List<String>,
        idx: Int,
        evictionStartedAt: Long,
        onAllDone: Runnable
    ) {
        if (idx >= pkgs.size) {
            Handler(Looper.getMainLooper()).post(onAllDone)
            return
        }
        val pkg = pkgs[idx]
        if (pkg.isEmpty()) {
            evictNext(svc, pkgs, idx + 1, evictionStartedAt, onAllDone)
            return
        }

        AppLogger.i(TAG, "evict: move→display0 $pkg")
        svc.moveTaskToDisplay(pkg, 0, object : ClusterService.LaunchCallback {
            override fun onResult(ok: Boolean) {
                AppLogger.i(TAG, "evict: move $pkg → " + (if (ok) "OK" else "KO") + " — awaiting landing")
                remove(pkg)
                awaitLandingThenForceStop(svc, pkgs, idx, evictionStartedAt, onAllDone)
            }
        })
    }

    /**
     * Waits for [pkgs]`[idx]` to be observed ON display 0, then force-stops it.
     *
     * `onResult` above fires when the move has been *requested*, not when it has happened — on a
     * ROM without `moveTaskToDisplay` the "move" is a relaunch, which is asynchronous. Killing on
     * that callback ended the app 2 ms later and left the system's last observation of the app
     * pointing at the cluster; see [ClusterEvictionPolicy] for the capture.
     *
     * Runs entirely off the main thread: [ProxyClient.findTaskLocationForPackage] is a blocking
     * binder call to the uid-2000 daemon and must never be issued from the UI thread.
     */
    private fun awaitLandingThenForceStop(
        svc: ClusterService,
        pkgs: List<String>,
        idx: Int,
        evictionStartedAt: Long,
        onAllDone: Runnable
    ) {
        val pkg = pkgs[idx]
        sLandingExecutor.execute {
            val waitStartedAt = SystemClock.elapsedRealtime()
            var landed = false
            var probes = 0
            // Last position actually OBSERVED, carried into the log. INC-20260729-201204 could
            // not be settled from the journal alone because nothing ever recorded where the app
            // was when we killed it — only that we had asked it to move. Never conclude from a
            // request again.
            var lastSeen = "never-probed"
            while (true) {
                probes++
                val match = try {
                    val location = ProxyClient.findTaskLocationForPackage(pkg)
                    lastSeen = "status=${location.status} task=${location.taskId} " +
                        "display=${location.displayId}"
                    location.matchDisplay(0)
                } catch (t: Throwable) {
                    // A failed probe says nothing about where the app is — keep waiting, bounded.
                    lastSeen = "probe failed: ${t.javaClass.simpleName}"
                    TaskLocation.DisplayMatch.UNKNOWN
                }
                val step = ClusterEvictionPolicy.next(
                    match, SystemClock.elapsedRealtime() - evictionStartedAt
                )
                if (step == ClusterEvictionPolicy.Step.LANDED) { landed = true; break }
                if (step == ClusterEvictionPolicy.Step.GIVE_UP) break
                if (!sleepQuietly(ClusterEvictionPolicy.POLL_INTERVAL_MS)) break
            }

            val waitedMs = SystemClock.elapsedRealtime() - waitStartedAt
            if (landed) {
                AppLogger.i(TAG, "evict: $pkg LANDED display=0 after ${waitedMs}ms "
                        + "($probes probes) — settling "
                        + "${ClusterEvictionPolicy.SETTLE_AFTER_LANDING_MS}ms before force-stop")
                sleepQuietly(ClusterEvictionPolicy.SETTLE_AFTER_LANDING_MS)
            } else {
                // Never skip the force-stop: an unverified landing is a display-affinity problem,
                // an app left alive on the cluster is a visible one.
                AppLogger.w(TAG, "evict: $pkg NOT-LANDED after ${waitedMs}ms ($probes probes, "
                        + "shared budget spent) lastSeen[$lastSeen] — force-stop anyway")
            }
            forceStopThenNext(svc, pkgs, idx, evictionStartedAt, onAllDone)
        }
    }

    private fun forceStopThenNext(
        svc: ClusterService,
        pkgs: List<String>,
        idx: Int,
        evictionStartedAt: Long,
        onAllDone: Runnable
    ) {
        val pkg = pkgs[idx]
        AdbLocalClient.forceStopApp(mAppCtx, pkg, object : AdbLocalClient.Callback {
            override fun onSuccess(r: String?) {
                evictNext(svc, pkgs, idx + 1, evictionStartedAt, onAllDone)
            }

            override fun onError(e: String?) {
                AppLogger.w(TAG, "evict: forceStop $pkg ERR: $e")
                evictNext(svc, pkgs, idx + 1, evictionStartedAt, onAllDone)
            }
        })
    }

    /** @return false if the thread was interrupted (caller must stop looping). */
    private fun sleepQuietly(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (ie: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun persist() {
        ClusterPrefs.setSessionClusterPkgs(mAppCtx, HashSet(mPkgs))
    }

    companion object {
        private const val TAG = "ClusterSessionTracker"

        /**
         * Single worker for the landing waits. Serial on purpose — eviction is already
         * sequential, and one daemon probe at a time keeps the binder pressure identical to
         * before. Daemon thread so it can never hold the process up at shutdown.
         */
        private val sLandingExecutor: ExecutorService =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "cluster-evict-landing").apply { isDaemon = true }
            }
    }
}
