package com.byd.dashcast.cluster

import com.byd.dashcast.infrastructure.task.TaskLocation
import com.byd.dashcast.proxy.daemon.TaskLaunchRecovery
import org.junit.Assert.*
import org.junit.Test

class RecoveredPlacementGuardTest {
    private val recovered = "${TaskLaunchRecovery.RECOVERED_PRIMARY_MARKER} task=42\nFINISH: launchAndForce complete."
    @Test fun `guard requires complete recovery and exact package display and resets`() {
        val guard = RecoveredPlacementGuard()
        guard.record("com.waze", 2, recovered)
        assertTrue(guard.preserves("com.waze", 2))
        assertFalse(guard.preserves("com.other", 2)); assertFalse(guard.preserves("com.waze", 3))
        guard.clear(); assertFalse(guard.preserves("com.waze", 2))
        for (invalid in listOf(null, TaskLaunchRecovery.RECOVERED_PRIMARY_MARKER,
                "FINISH: launchAndForce complete.")) {
            guard.record("com.waze", 2, recovered)
            guard.record("com.waze", 2, invalid)
            assertFalse(guard.preserves("com.waze", 2))
        }
    }
    @Test fun `historical cleanup exception cannot turn recovered primary into fallback eligible failure`() {
        val transcript = "EXCEPTION: cleanup unavailable\n" + recovered
        assertFalse(TaskLaunchRecovery.isSuccessful(transcript))
        val guard = RecoveredPlacementGuard()
        guard.record("com.waze", 2, transcript)
        assertTrue(guard.preserves("com.waze", 2))
        assertEquals(TaskLaunchRecovery.Outcome.PLACEMENT_UNCONFIRMED, TaskLaunchRecovery.outcome(transcript))
        assertEquals(TaskLaunchRecovery.Outcome.PLACEMENT_UNCONFIRMED,
            TaskLaunchRecovery.outcome(TaskLaunchRecovery.RECOVERED_PRIMARY_MARKER))
    }
    @Test fun `absence after recovered launch is terminal unconfirmed rather than fallback eligible`() {
        assertEquals(TaskLaunchRecovery.Outcome.PLACEMENT_UNCONFIRMED,
            TaskLaunchRecovery.verifyPlacement(TaskLaunchRecovery.Outcome.SUCCESS, TaskLocation.absent(), 2, true))
        assertEquals(TaskLaunchRecovery.Outcome.PLACEMENT_UNCONFIRMED,
            TaskLaunchRecovery.outcome(TaskLaunchRecovery.PLACEMENT_UNCONFIRMED_MARKER))
    }
}
