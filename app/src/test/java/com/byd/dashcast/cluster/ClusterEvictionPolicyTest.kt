package com.byd.dashcast.cluster

import com.byd.dashcast.infrastructure.task.TaskLocation.DisplayMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterEvictionPolicyTest {

    @Test
    fun `app confirmed on display 0 stops the wait immediately`() {
        assertEquals(
            ClusterEvictionPolicy.Step.LANDED,
            ClusterEvictionPolicy.next(DisplayMatch.ON_EXPECTED_DISPLAY, 0L)
        )
    }

    @Test
    fun `app still on the cluster keeps the wait going`() {
        assertEquals(
            ClusterEvictionPolicy.Step.KEEP_WAITING,
            ClusterEvictionPolicy.next(DisplayMatch.ON_OTHER_DISPLAY, 300L)
        )
    }

    @Test
    fun `task not created yet keeps the wait going — the relaunch is asynchronous`() {
        assertEquals(
            ClusterEvictionPolicy.Step.KEEP_WAITING,
            ClusterEvictionPolicy.next(DisplayMatch.ABSENT, 300L)
        )
    }

    @Test
    fun `a failed probe is not evidence and must not end the wait`() {
        assertEquals(
            ClusterEvictionPolicy.Step.KEEP_WAITING,
            ClusterEvictionPolicy.next(DisplayMatch.UNKNOWN, 300L)
        )
    }

    @Test
    fun `budget exhausted gives up so the force-stop still runs`() {
        assertEquals(
            ClusterEvictionPolicy.Step.GIVE_UP,
            ClusterEvictionPolicy.next(
                DisplayMatch.ON_OTHER_DISPLAY, ClusterEvictionPolicy.LANDING_BUDGET_MS
            )
        )
    }

    @Test
    fun `an unreachable daemon gives up rather than waiting forever`() {
        assertEquals(
            ClusterEvictionPolicy.Step.GIVE_UP,
            ClusterEvictionPolicy.next(
                DisplayMatch.UNKNOWN, ClusterEvictionPolicy.LANDING_BUDGET_MS + 1
            )
        )
    }

    @Test
    fun `a landing on the very last probe still counts as landed, not as a timeout`() {
        assertEquals(
            ClusterEvictionPolicy.Step.LANDED,
            ClusterEvictionPolicy.next(
                DisplayMatch.ON_EXPECTED_DISPLAY, ClusterEvictionPolicy.LANDING_BUDGET_MS + 5_000
            )
        )
    }

    @Test
    fun `the wait is bounded by a budget a driver would tolerate`() {
        // The budget is SHARED by the whole eviction, and onAllDone is what brings the OEM
        // cluster back — so this bounds how long Stop appears to hang, whatever the app count.
        // Two apps, both timing out, each settling: still under three seconds.
        val worstCaseTwoApps = ClusterEvictionPolicy.LANDING_BUDGET_MS +
            2 * ClusterEvictionPolicy.SETTLE_AFTER_LANDING_MS
        assertTrue("worst case was $worstCaseTwoApps ms", worstCaseTwoApps <= 3_000L)
        assertTrue(ClusterEvictionPolicy.POLL_INTERVAL_MS in 50L..250L)
        assertTrue(
            "at least four probes must fit in the budget",
            ClusterEvictionPolicy.LANDING_BUDGET_MS / ClusterEvictionPolicy.POLL_INTERVAL_MS >= 4
        )
    }

    @Test
    fun `a second app still gets probes when the first landed quickly`() {
        // The shared clock only starves later packages if an earlier one burned the budget.
        // A fast first landing (~300 ms measured on DiLink 3) must leave the second app usable.
        val afterFastFirstApp = 300L + ClusterEvictionPolicy.SETTLE_AFTER_LANDING_MS
        assertEquals(
            ClusterEvictionPolicy.Step.KEEP_WAITING,
            ClusterEvictionPolicy.next(DisplayMatch.ON_OTHER_DISPLAY, afterFastFirstApp)
        )
    }
}
