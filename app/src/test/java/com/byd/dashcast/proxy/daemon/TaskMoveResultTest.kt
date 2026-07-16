package com.byd.dashcast.proxy.daemon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskMoveResultTest {

    @Test
    fun directMethodFailureUsesSuccessfulStackFallback() {
        var fallbackCalls = 0
        val direct = "ERR moveTaskToDisplay: no (int,int) variant"
        val fallback = "WARN setTaskWindowingMode: IllegalArgumentException ; " +
            "stackId=53 currentDisplay=2 ; OK moveStackToDisplay(53,0)"

        val result = TaskMoveResult.runWithFallback(
            { direct },
            {
                fallbackCalls++
                fallback
            }
        )

        assertTrue(TaskMoveResult.isSuccess(result))
        assertTrue(result.contains("moveStackToDisplay(53,0)"))
        assertTrue(fallbackCalls == 1)
    }

    @Test
    fun directSuccessDoesNotNeedFallback() {
        var fallbackCalls = 0

        val result = TaskMoveResult.runWithFallback(
            { "OK moveTaskToDisplay(64,0)" },
            {
                fallbackCalls++
                "ERR should not run"
            }
        )

        assertTrue(TaskMoveResult.isSuccess(result))
        assertTrue(fallbackCalls == 0)
    }

    @Test
    fun twoFailedStrategiesRemainFailure() {
        val result = TaskMoveResult.combine(
            "ERR moveTaskToDisplay: no (int,int) variant",
            "WARN setTaskWindowingMode ; ERR no stack for task=64"
        )

        assertFalse(TaskMoveResult.isSuccess(result))
    }

    @Test
    fun alreadyOnTargetDisplayCountsAsSuccess() {
        assertTrue(TaskMoveResult.isSuccess("SKIP move (already on display 0)"))
    }
}
