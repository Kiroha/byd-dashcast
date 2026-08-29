package com.byd.dashcast.proxy.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The HUD event log, driven through its public surface.
 *
 * It had no test at all, and the defect it shipped was invisible for exactly that reason: the
 * buffer filled with the OLDEST thousand events and then ignored the rest, so
 * INC-20260826-194829 carried 1000 lines covering the first 36 seconds of a six-minute session
 * and said nothing about the five and a half minutes it had stopped listening to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CanFeedbackListenerBufferTest {

    /** Mirrors the private CAP. If that constant moves, this test should fail loudly. */
    private val cap = 1000

    @Before
    fun reset() = CanFeedbackListener.clear()

    @Test
    fun `a recording under the cap is kept whole and announces nothing`() {
        for (i in 1..10) CanFeedbackListener.mark("m$i")
        val out = CanFeedbackListener.drain()
        assertTrue(out, out.contains("TAP m1"))
        assertTrue(out, out.contains("TAP m10"))
        assertFalse("nothing was dropped, so nothing should be claimed", out.contains("[capped"))
    }

    @Test
    fun `past the cap the NEWEST events are the ones kept`() {
        for (i in 1..(cap + 200)) CanFeedbackListener.mark("m$i")
        val out = CanFeedbackListener.drain()
        // The whole point of the fix: the tail of the session is what a triager needs.
        assertTrue("the last event must survive", out.contains("TAP m${cap + 200}"))
        assertTrue(out.contains("TAP m${cap + 199}"))
        assertFalse("the oldest must be the ones dropped", out.contains("TAP m1\n"))
        assertEquals("exactly CAP events are kept", cap, out.lines().count { it.contains("TAP m") })
    }

    @Test
    fun `the drop is declared, and on the first line`() {
        for (i in 1..(cap + 37)) CanFeedbackListener.mark("m$i")
        val out = CanFeedbackListener.drain()
        val first = out.lines().first()
        assertTrue("a reader dates the session from what they see: $first", first.startsWith("[capped"))
        assertTrue(first, first.contains("37 event(s) dropped"))
        assertTrue(first, first.contains("$cap most recent kept"))
    }

    @Test
    fun `the drop count belongs to the window that dropped, not to the next one`() {
        for (i in 1..(cap + 5)) CanFeedbackListener.mark("m$i")
        assertTrue(CanFeedbackListener.drain().contains("[capped"))
        CanFeedbackListener.mark("after")
        val second = CanFeedbackListener.drain()
        assertTrue(second, second.contains("TAP after"))
        assertFalse("a drained window starts clean", second.contains("[capped"))
    }

    @Test
    fun `clear forgets the drops too`() {
        for (i in 1..(cap + 5)) CanFeedbackListener.mark("m$i")
        CanFeedbackListener.clear()
        CanFeedbackListener.mark("fresh")
        val out = CanFeedbackListener.drain()
        assertTrue(out, out.contains("TAP fresh"))
        assertFalse(out, out.contains("[capped"))
    }
}
