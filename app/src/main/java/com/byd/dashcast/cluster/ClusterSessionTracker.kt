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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the set of packages launched on the cluster display during this session.
 *
 * Provides add / remove / contains, and the full eviction pipeline
 * (probe → move → display 0 → force-stop) used by restoreBydDashboard / originCluster.
 *
 * The set is a session HISTORY, not a live inventory: entries are added on launch and only removed
 * when something proves the package is no longer on the cluster. Eviction therefore probes each
 * candidate before touching it, and skips the ones that already exited or are already on display 0.
 *
 * The set is persisted after every mutation so BootDisplayCleanup can recover
 * after a process death.
 */
class ClusterSessionTracker(context: Context) {

    fun interface EvictionCompletion {
        fun onAllDone(restoreHome: Boolean, callerComplete: Runnable)
    }

    private data class EvictionCandidate(
        val packageName: String,
        val token: PackageEvictionGenerationGate.Token,
    )

    private val mAppCtx: Context = context.applicationContext

    /** Main and landing threads share one mutation + snapshot + preference-write monitor. */
    private val mPkgs = PersistedPackageSet { packages ->
        ClusterPrefs.setSessionClusterPkgs(mAppCtx, packages)
    }

    // ── Set mutations ─────────────────────────────────────────────────────────

    fun add(pkg: String?) {
        if (pkg == null) return
        mPkgs.add(pkg)
    }

    fun remove(pkg: String?) {
        if (pkg == null) return
        mPkgs.remove(pkg)
    }

    fun contains(pkg: String?): Boolean = pkg != null && mPkgs.contains(pkg)

    /** Invalidates pending eviction work, or defers launch behind an issued force-stop. */
    fun runWhenSafeToLaunch(pkg: String, launch: Runnable) {
        val packageGuard = Runnable {
            if (sEvictionGate.prepareLaunch(pkg, launch)) {
                launch.run()
            } else {
                AppLogger.i(TAG, "launch deferred until force-stop completes: $pkg")
            }
        }
        if (sLaunchFence.prepareLaunch(pkg, packageGuard)) {
            packageGuard.run()
        } else {
            AppLogger.i(TAG, "launch deferred until eviction restoration completes: $pkg")
        }
    }

    private fun snapshot(): List<String> = mPkgs.snapshot()

    /**
     * Set when an eviction ends with an app we could not kill still holding a task on display 0.
     *
     * Keeping that task is correct — it is the app's way home — but it is left RESUMED and on top,
     * and INC-20260816 is what that costs: the OEM Android Auto host reclaimed the foreground and
     * the centre screen could not be used until the head unit was rebooted. The caller reads this
     * after the cluster has been handed back and puts the launcher in front.
     */
    private val mRestoreHome = HomeRestoreRequest()

    // ── Bulk operations ───────────────────────────────────────────────────────

    /**
     * Builds the eviction list, then sequentially moves each package to Display 0 and force-stops
     * it. Runs [onAllDone] on the main thread once every package has been processed.
     *
     * The list is [main] + [second] **plus everything still tracked**. Keying it off the caller's
     * two fields alone is what let INC-20260809-122719 through: `MainActivity` nulls
     * `mCurrentDashboardPkg` as soon as an app is sent to the main display, so Stop projection
     * arrived with both fields null, the list was empty, and the whole pipeline was skipped for an
     * app that had never actually left the cluster. The tracker is the authoritative answer to
     * "what may still hold a cluster task".
     *
     * Widening the list is only safe because [evictNext] now LOOKS before it acts — see there. The
     * tracked set is a session history, so most of its entries are usually nothing to evict.
     */
    fun evictAllThen(svc: ClusterService?, main: String?, second: String?,
                     onAllDone: EvictionCompletion) {
        sLaunchFence.beginOperation()
        val fenceReleased = AtomicBoolean(false)
        val releaseFence = Runnable {
            if (fenceReleased.compareAndSet(false, true)) sLaunchFence.finishOperation()
        }
        try {
            sEvictionOperations.submit({ lease ->
                evictAllOwned(svc, main, second, onAllDone, lease)
            }, releaseFence)
        } catch (error: Throwable) {
            releaseFence.run()
            throw error
        }
    }

    private fun evictAllOwned(svc: ClusterService?, main: String?, second: String?,
                              onAllDone: EvictionCompletion,
                              lease: EvictionOperationQueue.Lease) {
        mRestoreHome.reset()
        if (svc == null) {
            // Nothing here can verify anything: no service to move with, and probing from the main
            // thread is not an option. Stay with the two packages the caller knows were on screen
            // rather than force-stopping a whole session's history on a guess.
            val blind = ClusterEvictionPolicy.evictionList(main, second, emptyList())
            if (blind.isEmpty()) {
                lease.markPhysicalDone()
                deliverCompletion(onAllDone, lease)
                return
            }
            // No home cover from this path, deliberately. It is blind by definition — there is
            // no service to move with and no probe to reason from — so it cannot establish the one
            // precondition the cover requires: that the app is alive ON display 0. Asserting that
            // without evidence is the defect this whole pipeline was rewritten to stop making.
            val main = Handler(Looper.getMainLooper())
            val blindTokens = ArrayList<PackageEvictionGenerationGate.Token>(blind.size)
            val physicalRemaining = AtomicInteger(blind.size)
            fun physicalComplete() {
                if (physicalRemaining.decrementAndGet() == 0) {
                    main.post { lease.markPhysicalDone() }
                }
            }
            lateinit var timeout: Runnable
            val barrier = BoundedCompletionBarrier(blind.size, Runnable {
                main.removeCallbacks(timeout)
                deliverCompletion(onAllDone, lease)
            })
            timeout = Runnable {
                if (barrier.timeout()) {
                    for (token in blindTokens) {
                        sEvictionGate.discardDeferredLaunch(token)
                    }
                    AppLogger.w(TAG, "blind eviction timeout — prior deferred launches discarded")
                }
            }
            main.postDelayed(timeout, BLIND_EVICTION_TIMEOUT_MS)
            for (p in blind) {
                val token = sEvictionGate.beginEviction(p)
                blindTokens += token
                add(p)
                if (!sEvictionGate.tryBeginDestructive(token)) {
                    barrier.complete(Runnable {})
                    physicalComplete()
                    continue
                }
                val completed = AtomicBoolean(false)
                fun completeBlind(success: Boolean, error: String?) {
                    if (!completed.compareAndSet(false, true)) return
                    val completion = sEvictionGate.finishDestructive(token, Runnable {
                        if (success) remove(p)
                    })
                    postDeferredLaunch(completion?.deferredLaunch)
                    if (error != null) {
                        AppLogger.w(TAG, "blind forceStop $p failed: $error — retained")
                    }
                    barrier.complete(Runnable {})
                    physicalComplete()
                }
                try {
                    AdbLocalClient.forceStopAppForBlindEviction(
                        mAppCtx, p, object : AdbLocalClient.Callback {
                        override fun onSuccess(result: String?) = completeBlind(true, null)
                        override fun onError(error: String?) = completeBlind(false, error)
                    })
                } catch (error: Throwable) {
                    AppLogger.e(TAG, "blind forceStop dispatch failed for $p", error)
                    completeBlind(false, error.message)
                }
            }
            return
        }

        val pkgs = ClusterEvictionPolicy.evictionList(main, second, snapshot())
        if (pkgs.isEmpty()) {
            lease.markPhysicalDone()
            deliverCompletion(onAllDone, lease)
            return
        }
        // Every candidate remains recoverable across process death until a later probe or kill
        // proves a safe final state. This also covers main/second arguments not already in history.
        val candidates = pkgs.map { pkg ->
            val token = sEvictionGate.beginEviction(pkg)
            add(pkg)
            EvictionCandidate(pkg, token)
        }
        AppLogger.i(TAG, "evictAll: ${pkgs.size} candidate(s) → $pkgs")
        // One clock for the whole eviction — see ClusterEvictionPolicy.LANDING_BUDGET_MS.
        evictNext(svc, candidates, 0, SystemClock.elapsedRealtime(), Runnable {
            lease.markPhysicalDone()
            deliverCompletion(onAllDone, lease)
        })
    }

    private fun deliverCompletion(
        completion: EvictionCompletion,
        lease: EvictionOperationQueue.Lease,
    ) {
        val restoreHome = mRestoreHome.consume()
        Handler(Looper.getMainLooper()).post {
            try {
                completion.onAllDone(restoreHome, Runnable { lease.markCallerDone() })
            } catch (error: Throwable) {
                lease.markCallerDone()
                throw error
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun evictNext(
        svc: ClusterService,
        candidates: List<EvictionCandidate>,
        idx: Int,
        evictionStartedAt: Long,
        onAllDone: Runnable
    ) {
        if (idx >= candidates.size) {
            Handler(Looper.getMainLooper()).post(onAllDone)
            return
        }
        val candidate = candidates[idx]
        val pkg = candidate.packageName
        if (pkg.isEmpty()) {
            evictNext(svc, candidates, idx + 1, evictionStartedAt, onAllDone)
            return
        }
        if (!sEvictionGate.isCurrent(candidate.token)) {
            evictNext(svc, candidates, idx + 1, evictionStartedAt, onAllDone)
            return
        }
        // LOOK before acting. The list is a session history, so most entries need nothing done —
        // and doing something anyway is destructive, not merely wasteful: ClusterService's
        // moveTaskToDisplay falls back to a LAUNCH when the package has no task, so evicting a
        // package that already exited would cold-start it on display 0 only to force-stop it a
        // moment later. Probing costs one binder round trip and is the only way to tell the app
        // stranded on the cluster (the one this whole pipeline exists for) from the ones that are
        // simply gone or already home.
        sLandingExecutor.execute {
            val location = try {
                ProxyClient.findTaskLocationForPackage(pkg)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "evict: probe failed for $pkg (${t.javaClass.simpleName}) "
                        + "— evicting anyway, an app left on the cluster is the worse outcome")
                TaskLocation.unknown()
            }
            if (!sEvictionGate.isCurrent(candidate.token)) {
                evictNext(svc, candidates, idx + 1, evictionStartedAt, onAllDone)
                return@execute
            }
            val skip = when (location.matchDisplay(0)) {
                // Already home: the user may well be using it right now. Never kill it.
                TaskLocation.DisplayMatch.ON_EXPECTED_DISPLAY -> "already on display 0"
                // No task at all — nothing to move, nothing to kill.
                TaskLocation.DisplayMatch.ABSENT -> "no task"
                else -> null
            }
            if (skip != null) {
                AppLogger.i(TAG, "evict: skip $pkg ($skip) — untracked")
                sEvictionGate.runIfCurrent(candidate.token, Runnable { remove(pkg) })
                Handler(Looper.getMainLooper()).post {
                    evictNext(svc, candidates, idx + 1, evictionStartedAt, onAllDone)
                }
                return@execute
            }
            AppLogger.i(TAG, "evict: move→display0 $pkg (was display=${location.displayId})")
            Handler(Looper.getMainLooper()).post {
                val main = Handler(Looper.getMainLooper())
                val continued = AtomicBoolean(false)
                lateinit var timeout: Runnable
                fun continueAfterMove(ok: Boolean, source: String) {
                    if (!continued.compareAndSet(false, true)) return
                    main.removeCallbacks(timeout)
                    if (!sEvictionGate.isCurrent(candidate.token)) {
                        evictNext(svc, candidates, idx + 1, evictionStartedAt, onAllDone)
                        return
                    }
                    AppLogger.i(TAG, "evict: move $pkg → "
                            + (if (ok) "OK" else "KO") + " ($source) — awaiting landing")
                    awaitLandingThenForceStop(
                        svc, candidates, idx, evictionStartedAt, onAllDone
                    )
                }
                timeout = Runnable {
                    AppLogger.w(TAG, "evict: move callback timeout for $pkg — continuing safely")
                    continueAfterMove(false, "callback-timeout")
                }
                main.postDelayed(timeout, MOVE_CALLBACK_TIMEOUT_MS)
                try {
                    svc.moveTaskToDisplay(pkg, 0, object : ClusterService.LaunchCallback {
                        override fun onResult(ok: Boolean) {
                            continueAfterMove(ok, "callback")
                        }
                    })
                } catch (error: Throwable) {
                    AppLogger.e(TAG, "evict: move dispatch failed for $pkg", error)
                    continueAfterMove(false, "dispatch-error")
                }
            }
        }
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
        candidates: List<EvictionCandidate>,
        idx: Int,
        evictionStartedAt: Long,
        onAllDone: Runnable
    ) {
        val candidate = candidates[idx]
        val pkg = candidate.packageName
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
                if (!sEvictionGate.isCurrent(candidate.token)) {
                    evictNext(svc, candidates, idx + 1, evictionStartedAt, onAllDone)
                    return@execute
                }
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
            forceStopThenNext(svc, candidates, idx, evictionStartedAt, onAllDone)
        }
    }

    private fun forceStopThenNext(
        svc: ClusterService,
        candidates: List<EvictionCandidate>,
        idx: Int,
        evictionStartedAt: Long,
        onAllDone: Runnable
    ) {
        val candidate = candidates[idx]
        val pkg = candidate.packageName
        if (!sEvictionGate.tryBeginDestructive(candidate.token)) {
            evictNext(svc, candidates, idx + 1, evictionStartedAt, onAllDone)
            return
        }
        val completed = AtomicBoolean(false)
        fun completeForceStop(success: Boolean, error: String?) {
            if (!completed.compareAndSet(false, true)) return
            val completion = sEvictionGate.finishDestructive(candidate.token, Runnable {
                if (success) remove(pkg) else add(pkg)
            })
            postDeferredLaunch(completion?.deferredLaunch)
            if (error != null) AppLogger.w(TAG, "evict: forceStop $pkg ERR: $error")
            evictNext(svc, candidates, idx + 1, evictionStartedAt, onAllDone)
        }
        try {
            AdbLocalClient.forceStopApp(mAppCtx, pkg, object : AdbLocalClient.Callback {
                override fun onSuccess(r: String?) = completeForceStop(true, null)

                override fun onEvictionOutcome(outcome: EvictionOutcomePolicy.Outcome) {
                // Reported by forceStopApp from the probe it took itself, immediately before the
                // kill. Deciding again here from the landing-wait's last probe was the first
                // version's bug: on the give-up branch that copy is stale by construction and can
                // never say KEEP_AND_RESTORE_HOME, even when the task did land on display 0.
                    sEvictionGate.runIfCurrent(candidate.token, Runnable {
                        if (outcome == EvictionOutcomePolicy.Outcome.KEEP_AND_RESTORE_HOME) {
                            AppLogger.i(TAG, "evict: $pkg survived the kill on display 0 — "
                                    + "the home screen will be restored in front of it")
                        }
                        mRestoreHome.arm(outcome)
                    })
                }

                override fun onError(e: String?) = completeForceStop(false, e)
            })
        } catch (error: Throwable) {
            AppLogger.e(TAG, "evict: forceStop dispatch failed for $pkg", error)
            completeForceStop(false, error.message)
        }
    }

    private fun postDeferredLaunch(launch: Runnable?) {
        if (launch != null) Handler(Looper.getMainLooper()).post(launch)
    }

    /** @return false if the thread was interrupted (caller must stop looping). */
    private fun sleepQuietly(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (ie: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    companion object {
        private const val TAG = "ClusterSessionTracker"
        private const val MOVE_CALLBACK_TIMEOUT_MS = 30_000L
        private const val BLIND_EVICTION_TIMEOUT_MS = 30_000L
        private val sEvictionGate = PackageEvictionGenerationGate()
        private val sLaunchFence = EvictionLaunchFence()
        private val sEvictionOperations = EvictionOperationQueue()

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
