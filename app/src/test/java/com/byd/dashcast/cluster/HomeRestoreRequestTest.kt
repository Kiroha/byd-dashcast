package com.byd.dashcast.cluster

import com.byd.dashcast.cluster.EvictionOutcomePolicy.Outcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRestoreRequestTest {

    @Test
    fun `a fresh request asks for nothing`() {
        assertFalse(HomeRestoreRequest().consume())
    }

    @Test
    fun `only the outcome that means an app is stranded on display 0 arms it`() {
        for (outcome in Outcome.values()) {
            val r = HomeRestoreRequest()
            r.arm(outcome)
            val expected = outcome == Outcome.KEEP_AND_RESTORE_HOME
            org.junit.Assert.assertEquals("$outcome", expected, r.consume())
        }
    }

    /**
     * Both teardown call sites ask — the restore callback's success branch and its failure branch,
     * because the eviction created the hazard regardless of what that later call did. Exactly one
     * of them must get a yes.
     */
    @Test
    fun `it is consumed exactly once`() {
        val r = HomeRestoreRequest()
        r.arm(Outcome.KEEP_AND_RESTORE_HOME)
        assertTrue(r.consume())
        assertFalse("a second reader must not launch the home screen again", r.consume())
    }

    /**
     * The stale-flag hazard: an armed request that nobody consumed must never fire on some later,
     * unrelated Stop press.
     */
    @Test
    fun `an eviction never inherits an earlier request`() {
        val r = HomeRestoreRequest()
        r.arm(Outcome.KEEP_AND_RESTORE_HOME)
        r.reset()
        assertFalse(r.consume())
    }

    @Test
    fun `several stranded packages in one eviction still ask once`() {
        val r = HomeRestoreRequest()
        r.arm(Outcome.KEEP_AND_RESTORE_HOME)
        r.arm(Outcome.KEEP_AND_RESTORE_HOME)
        assertTrue(r.consume())
        assertFalse(r.consume())
    }
}
