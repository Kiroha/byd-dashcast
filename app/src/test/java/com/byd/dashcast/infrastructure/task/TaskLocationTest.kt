package com.byd.dashcast.infrastructure.task

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskLocationTest {

    @Test
    fun foundTaskOnExpectedDisplayIsAdopted() {
        val location = TaskLocation.found(taskId = 42, displayId = 2)

        assertEquals(
            TaskLocation.DisplayMatch.ON_EXPECTED_DISPLAY,
            location.matchDisplay(2)
        )
    }

    @Test
    fun foundTaskOnAnotherDisplayIsRelaunched() {
        val location = TaskLocation.found(taskId = 42, displayId = 0)

        assertEquals(
            TaskLocation.DisplayMatch.ON_OTHER_DISPLAY,
            location.matchDisplay(2)
        )
    }

    @Test
    fun absentTaskIsRelaunched() {
        assertEquals(
            TaskLocation.DisplayMatch.ABSENT,
            TaskLocation.absent().matchDisplay(2)
        )
    }

    @Test
    fun failedLookupIsRetriedWithoutRelaunch() {
        assertEquals(
            TaskLocation.DisplayMatch.UNKNOWN,
            TaskLocation.unknown().matchDisplay(2)
        )
    }

    @Test
    fun foundTaskWithUnreadableDisplayIsUnknown() {
        assertEquals(
            TaskLocation.DisplayMatch.UNKNOWN,
            TaskLocation.found(taskId = 42, displayId = -1).matchDisplay(2)
        )
    }

    @Test
    fun unrecognizedWireStatusIsUnknown() {
        assertEquals(
            TaskLocation.Status.UNKNOWN,
            TaskLocation.fromWire(statusCode = 99, taskId = 42, displayId = 2).status
        )
    }
}
