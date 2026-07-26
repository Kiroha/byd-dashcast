package com.byd.dashcast.proxy.daemon

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
}
