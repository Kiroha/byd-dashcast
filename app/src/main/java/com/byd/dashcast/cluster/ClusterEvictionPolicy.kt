package com.byd.dashcast.cluster

import com.byd.dashcast.infrastructure.task.TaskLocation

/**
 * Decides how long to wait for an evicted app to actually LAND on display 0 before killing it.
 *
 * Why this exists (INC-20260729-201204, DiLink 3, 1.8.6-beta). Stopping projection used to
 * force-stop each projected app **2 milliseconds** after asking it to return to display 0:
 *
 * ```
 * 20:11:46.196  DashboardLauncher  Context.startActivity OK display=0
 * 20:11:46.198  AdbLocalClient     forceStop org.schabi.newpipe
 * ```
 *
 * The request was issued and the app was killed before it could be honoured, so the last state
 * the system ever observed for that component was "on the cluster". Four seconds later, tapping
 * the app in the launcher on the CENTRE screen re-opened it on the cluster — captured verbatim:
 * task #42 on Display #1 with `launchedFromPackage=com.lexwah.kinex`, i.e. the launcher, versus
 * the same app in the earlier capture with `launchedFromPackage=com.android.shell`, i.e. us.
 * It affected every projected app, not one.
 *
 * On this ROM `IActivityTaskManager.moveTaskToDisplay` does not exist ("method unavailable on
 * ROM → fallback launch"), so the app is *relaunched* on display 0 rather than moved. A relaunch
 * is asynchronous: the only sound way to know it worked is to look.
 *
 * Deliberately a bounded WAIT, not a retry: it never re-issues the move and never skips the
 * force-stop. Failing to confirm is not a reason to leave an app alive on the cluster — that
 * would trade a display-affinity bug for a much worse one.
 */
object ClusterEvictionPolicy {

    /** Gap between two location probes. Each probe is a binder round trip to the proxy daemon. */
    const val POLL_INTERVAL_MS = 150L

    /**
     * Hard ceiling on the waiting, **shared by the whole eviction** rather than granted per
     * package.
     *
     * What the user is waiting for is not one app: `onAllDone` is what triggers
     * `restoreBydOnCluster`, so every millisecond spent here delays the OEM cluster coming back
     * after they pressed Stop. Budgeting per package would multiply that delay by the number of
     * projected apps; budgeting the total keeps the worst case flat. Measured landing on
     * DiLink 3 is well under 300 ms, so two apps normally consume a fraction of this.
     */
    const val LANDING_BUDGET_MS = 2_000L

    /**
     * Grace period after the landing is confirmed, before the force-stop.
     *
     * The system records a task's display when the task's configuration settles, not when
     * `startActivity` returns. Confirming the landing and killing in the same breath risks
     * racing that write — which is the whole defect this class exists to close.
     */
    const val SETTLE_AFTER_LANDING_MS = 250L

    /**
     * The packages Stop projection must evict: [main] + [second] first (they are what the user
     * just had on screen, so they should be dealt with first), then everything still [tracked].
     *
     * Including [tracked] is the fix for INC-20260809-122719. `MainActivity` clears its
     * `mCurrentDashboardPkg` as soon as an app is sent to the main display, so a Stop projection
     * that follows arrived with `main` and `second` both null; the list was empty and the entire
     * verified pipeline was skipped — for an app that, on that ROM, had never actually left the
     * cluster. The session tracker is the authoritative answer to "what may still hold a cluster
     * task", so it belongs in this list; the caller's two fields alone never did.
     *
     * Order-preserving and deduplicated, blanks dropped.
     */
    @JvmStatic
    fun evictionList(main: String?, second: String?, tracked: Collection<String>): List<String> {
        val set = LinkedHashSet<String>()
        for (candidate in sequenceOf(main, second) + tracked.asSequence()) {
            if (!candidate.isNullOrBlank()) set.add(candidate)
        }
        return ArrayList(set)
    }

    enum class Step {
        /** Confirmed on display 0 — settle, then force-stop. */
        LANDED,

        /** Not there yet and budget remains — probe again. */
        KEEP_WAITING,

        /** Budget exhausted — force-stop anyway rather than leave it running. */
        GIVE_UP
    }

    /**
     * @param match    where the package's task is, relative to display 0
     * @param elapsedMs time since the **eviction** started — not since this package's move, see
     *                  [LANDING_BUDGET_MS]
     *
     * [TaskLocation.DisplayMatch.ABSENT] keeps waiting on purpose: the relaunch is in flight and
     * the task legitimately does not exist yet. [TaskLocation.DisplayMatch.UNKNOWN] likewise —
     * a transport failure is not evidence about where the app is, and treating it as an answer
     * is the mistake [TaskLocation] was written to prevent. Both are bounded by the budget.
     */
    @JvmStatic
    fun next(match: TaskLocation.DisplayMatch, elapsedMs: Long): Step = when {
        match == TaskLocation.DisplayMatch.ON_EXPECTED_DISPLAY -> Step.LANDED
        elapsedMs >= LANDING_BUDGET_MS -> Step.GIVE_UP
        else -> Step.KEEP_WAITING
    }
}
