package com.byd.dashcast.proxy.daemon

import com.byd.dashcast.infrastructure.task.TaskLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLaunchRecoveryTest {

    @Test
    fun incidentWindowManagerNpeRequiresCleanPlainRecovery() {
        val transcript = """
            Starting: Intent { flg=0x10000 cmp=com.waze/.FreeMapAppActivity }
            Exception occurred while executing:
            java.lang.NullPointerException: Attempt to invoke virtual method
            'android.graphics.Rect com.android.server.wm.ActivityStack.getBounds()'
            on a null object reference
        """.trimIndent()

        assertTrue(TaskLaunchRecovery.isStartFailure(transcript))
        assertTrue(TaskLaunchRecovery.isFreeformStackFailure(transcript))
    }

    @Test
    fun ordinarySuccessfulStartDoesNotTriggerFrameworkRecovery() {
        val transcript = "Status: ok\nActivity: com.waze/.FreeMapAppActivity"

        assertFalse(TaskLaunchRecovery.isStartFailure(transcript))
        assertFalse(TaskLaunchRecovery.isFreeformStackFailure(transcript))
    }

    @Test
    fun retryCleansPoisonedDisplayBeforePlainLaunchAndPoll() {
        val events = mutableListOf<String>()

        val taskId = TaskLaunchRecovery.retryOnCleanDisplay(
            object : TaskLaunchRecovery.Operations {
                override fun cleanDisplay(): String {
                    events += "clean"
                    return "removed zombie"
                }

                override fun launchPlain() {
                    events += "plain"
                }

                override fun pollTask(): Int {
                    events += "poll"
                    return 73
                }
            }
        )

        assertEquals(73, taskId)
        assertEquals(listOf("clean", "plain", "poll"), events)
    }

    @Test
    fun completedCascadeIsSuccessfulDespiteDiagnosticErrLines() {
        val result = """
            ERR moveStackToDisplay: already on current display
            ERR resizeTask: not allowed
            WATCHDOG started
            FINISH: launchAndForce complete.
        """.trimIndent()

        assertTrue(TaskLaunchRecovery.isSuccessful(result))
        assertFalse(TaskLaunchRecovery.isSuccessful("FAIL: no task discovered for com.waze"))
    }

    @Test
    fun partialOrAbortedLaunchCannotEstablishSuccess() {
        assertFalse(TaskLaunchRecovery.isSuccessful(null))
        assertFalse(TaskLaunchRecovery.isSuccessful("Starting: Intent {cmp=com.waze/.Main}"))
        assertFalse(TaskLaunchRecovery.isSuccessful("findTask(post-poll) = 73\nEXCEPTION: failed move"))
        assertFalse(TaskLaunchRecovery.isSuccessful(
            "EXCEPTION: failed move\nFINISH: launchAndForce complete.\n"))
    }

    @Test
    fun onlyTerminalFrameworkFailureWithConfirmedAbsenceStopsFurtherFallbacks() {
        val npe = "Exception occurred while executing: NullPointerException ActivityStack.getBounds()"
        assertTrue(TaskLaunchRecovery.isBlockedAfterPlainLaunch(npe, TaskLocation.absent()))
        assertFalse(TaskLaunchRecovery.isBlockedAfterPlainLaunch(npe, TaskLocation.unknown()))
        assertFalse(TaskLaunchRecovery.isBlockedAfterPlainLaunch(npe, TaskLocation.found(73, 9)))
        assertFalse(TaskLaunchRecovery.isBlockedAfterPlainLaunch("Error: permission denied", TaskLocation.absent()))
        assertFalse(TaskLaunchRecovery.isBlockedAfterPlainLaunch(null, TaskLocation.absent()))
    }

    @Test
    fun terminalFrameworkMarkerIsSeparateFromOrdinaryFailureAndRecoveredNpe() {
        assertEquals(TaskLaunchRecovery.Outcome.FRAMEWORK_BLOCKED,
            TaskLaunchRecovery.outcome(TaskLaunchRecovery.FRAMEWORK_BLOCKED_MARKER +
                "\nFAIL: no task discovered for com.waze"))
        assertEquals(TaskLaunchRecovery.Outcome.FAILED,
            TaskLaunchRecovery.outcome("ActivityStack.getBounds()\nFAIL: no task discovered"))
        assertEquals(TaskLaunchRecovery.Outcome.SUCCESS,
            TaskLaunchRecovery.outcome("ActivityStack.getBounds()\nFINISH: launchAndForce complete."))
    }

    @Test
    fun completedCascadeRequiresProofOnTargetBeforeSuccess() {
        val completed = TaskLaunchRecovery.Outcome.SUCCESS
        assertEquals(completed, TaskLaunchRecovery.verifyPlacement(completed, TaskLocation.found(73, 9), 9))
        assertEquals(TaskLaunchRecovery.Outcome.PLACEMENT_UNCONFIRMED,
            TaskLaunchRecovery.verifyPlacement(completed, TaskLocation.unknown(), 9))
        assertEquals(TaskLaunchRecovery.Outcome.PLACEMENT_UNCONFIRMED,
            TaskLaunchRecovery.verifyPlacement(completed, TaskLocation.found(73, 0), 9))
        assertEquals(TaskLaunchRecovery.Outcome.FAILED,
            TaskLaunchRecovery.verifyPlacement(completed, TaskLocation.absent(), 9))
    }
}
