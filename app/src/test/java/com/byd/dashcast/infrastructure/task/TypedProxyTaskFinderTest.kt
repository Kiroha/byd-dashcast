package com.byd.dashcast.infrastructure.task

import org.junit.Assert.assertEquals
import org.junit.Test

class TypedProxyTaskFinderTest {

    @Test
    fun preservesValidTaskId() {
        assertEquals(42, TypedProxyTaskFinder.normalizeTaskId(42))
    }

    @Test
    fun mapsNonPositiveResultsToNotFound() {
        assertEquals(TaskFinder.NOT_FOUND, TypedProxyTaskFinder.normalizeTaskId(0))
        assertEquals(TaskFinder.NOT_FOUND, TypedProxyTaskFinder.normalizeTaskId(-1))
    }
}