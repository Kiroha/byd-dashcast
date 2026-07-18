package com.byd.dashcast.proxy.daemon

import com.byd.dashcast.infrastructure.task.TaskLocation
import org.junit.Assert.assertEquals
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
}