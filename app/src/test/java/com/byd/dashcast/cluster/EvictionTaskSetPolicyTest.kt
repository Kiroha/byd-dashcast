package com.byd.dashcast.cluster

import com.byd.dashcast.infrastructure.task.TaskLocation
import org.junit.Assert.assertEquals
import org.junit.Test

class EvictionTaskSetPolicyTest {

    @Test
    fun `failed kill removes cluster tasks but retains center and requests home`() {
        val decision = EvictionTaskSetPolicy.decide(false, listOf(
            TaskLocation.found(7, 1),
            TaskLocation.found(8, 0),
            TaskLocation.found(9, 3),
        ))

        assertEquals(EvictionOutcomePolicy.Outcome.KEEP_AND_RESTORE_HOME, decision.outcome)
        assertEquals(listOf(7, 9), decision.taskIdsToRemove)
    }

    @Test
    fun `verified kill removes every known task`() {
        val decision = EvictionTaskSetPolicy.decide(true, listOf(
            TaskLocation.found(7, 1),
            TaskLocation.found(8, 0),
        ))

        assertEquals(EvictionOutcomePolicy.Outcome.REMOVE_TASK, decision.outcome)
        assertEquals(listOf(7, 8), decision.taskIdsToRemove)
    }
}