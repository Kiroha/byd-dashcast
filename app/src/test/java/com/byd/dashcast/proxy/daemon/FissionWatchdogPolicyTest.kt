package com.byd.dashcast.proxy.daemon

import com.byd.dashcast.infrastructure.task.TaskLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FissionWatchdogPolicyTest {
    @Test
    fun `incident late Waze bounce at thirteen seconds is reanchored`() {
        val policy = FissionWatchdogPolicy()
        repeat(27) { index ->
            assertEquals(
                FissionWatchdogPolicy.Action.WAIT,
                policy.onPoll(index + 1, TaskLocation.DisplayMatch.ON_EXPECTED_DISPLAY)
            )
        }

        assertEquals(
            FissionWatchdogPolicy.Action.REANCHOR,
            policy.onPoll(28, TaskLocation.DisplayMatch.ON_OTHER_DISPLAY)
        )
    }

    @Test
    fun `stable task is watched for full startup horizon`() {
        val policy = FissionWatchdogPolicy()
        for (poll in 1 until FissionWatchdogPolicy.INITIAL_GUARD_POLLS) {
            assertEquals(
                FissionWatchdogPolicy.Action.WAIT,
                policy.onPoll(poll, TaskLocation.DisplayMatch.ON_EXPECTED_DISPLAY)
            )
        }
        assertEquals(
            FissionWatchdogPolicy.Action.COMPLETE,
            policy.onPoll(
                FissionWatchdogPolicy.INITIAL_GUARD_POLLS,
                TaskLocation.DisplayMatch.ON_EXPECTED_DISPLAY
            )
        )
    }

    @Test
    fun `late reanchor extends verification and repeated bounce remains recoverable`() {
        val policy = FissionWatchdogPolicy()
        assertEquals(
            FissionWatchdogPolicy.Action.REANCHOR,
            policy.onPoll(55, TaskLocation.DisplayMatch.ON_OTHER_DISPLAY)
        )
        assertEquals(85, policy.completionPoll())
        assertEquals(
            FissionWatchdogPolicy.Action.WAIT,
            policy.onPoll(60, TaskLocation.DisplayMatch.ON_EXPECTED_DISPLAY)
        )
        assertEquals(
            FissionWatchdogPolicy.Action.REANCHOR,
            policy.onPoll(80, TaskLocation.DisplayMatch.ON_OTHER_DISPLAY)
        )
        assertEquals(110, policy.completionPoll())
        assertEquals(
            FissionWatchdogPolicy.Action.COMPLETE,
            policy.onPoll(110, TaskLocation.DisplayMatch.ON_EXPECTED_DISPLAY)
        )
    }

    @Test
    fun `transient absent or unknown task does not end guardian early`() {
        val policy = FissionWatchdogPolicy()
        assertEquals(
            FissionWatchdogPolicy.Action.WAIT,
            policy.onPoll(30, TaskLocation.DisplayMatch.ABSENT)
        )
        assertEquals(
            FissionWatchdogPolicy.Action.WAIT,
            policy.onPoll(59, TaskLocation.DisplayMatch.UNKNOWN)
        )
        assertEquals(
            FissionWatchdogPolicy.Action.COMPLETE,
            policy.onPoll(FissionWatchdogPolicy.MAX_POLLS, TaskLocation.DisplayMatch.UNKNOWN)
        )
    }

    @Test
    fun `wrong display task wins over another task already in slot`() {
        val selected = FissionWatchdogPolicy.selectTask(
            listOf(TaskLocation.found(10, 4), TaskLocation.found(11, 0)),
            4
        )

        assertEquals(11, selected.taskId)
        assertEquals(0, selected.displayId)
    }

    @Test
    fun `unknown matching task prevents premature stable verdict`() {
        val selected = FissionWatchdogPolicy.selectTask(
            listOf(TaskLocation.found(10, 4), TaskLocation.unknown()),
            4
        )

        assertEquals(TaskLocation.Status.UNKNOWN, selected.status)
    }

    @Test
    fun `null Java list entries are ignored without hiding a displaced task`() {
        val selected = FissionWatchdogPolicy.selectTask(
            listOf(null, TaskLocation.found(11, 0)),
            4
        )

        assertEquals(11, selected.taskId)
        assertEquals(0, selected.displayId)
    }
    // ── what the daemon transcript gets to see ───────────────────────────────────────────────

    /**
     * The watchdog used to be unobservable: `WATCHDOG started .. max=90s` was its only trace in
     * INC-20260826-194829's 10,097 lines, because every other line went to android.util.Log and
     * no DashCast tag reaches this ROM's logcat. Its lines are now mirrored into the daemon
     * transcript, which means the four verb transcripts they carry have to be cut down first.
     */
    @Test
    fun `a verb transcript is reduced to its first line`() {
        assertEquals("OK setFocusedTask(26)",
            FissionWatchdogPolicy.brief("OK setFocusedTask(26)\nignored detail\nmore"))
    }

    @Test
    fun `a long first line is truncated visibly`() {
        val out = FissionWatchdogPolicy.brief("x".repeat(200))
        assertEquals(62, out.length)
        assertTrue("the cut must be visible, not silent", out.endsWith(".."))
    }

    @Test
    fun `nothing to report reads as a dash rather than an empty gap`() {
        // A blank field in a transcript line reads as a parsing accident. A dash is a statement.
        assertEquals("-", FissionWatchdogPolicy.brief(null))
        assertEquals("-", FissionWatchdogPolicy.brief(""))
        assertEquals("-", FissionWatchdogPolicy.brief("   \n  "))
    }
    /**
     * The transcript reaches a report as `tail -200`. Mirroring all 180 possible re-anchors would
     * evict the daemon's boot lines and version-gate verdict from that window — in exactly the
     * session the mirroring exists to document.
     */
    @Test
    fun `a re-anchor storm cannot flood the daemon transcript out of the report`() {
        val mirrored = (1..FissionWatchdogPolicy.MAX_POLLS)
            .count { FissionWatchdogPolicy.shouldMirrorReanchor(it) }
        assertTrue("a full storm must stay well inside the 200-line window, was $mirrored",
            mirrored <= 20)
    }

    @Test
    fun `the first few re-anchors are always shown`() {
        for (n in 1..5) assertTrue("$n", FissionWatchdogPolicy.shouldMirrorReanchor(n))
        assertFalse(FissionWatchdogPolicy.shouldMirrorReanchor(6))
        assertTrue("one in twenty keeps showing it continues",
            FissionWatchdogPolicy.shouldMirrorReanchor(20))
    }
}
