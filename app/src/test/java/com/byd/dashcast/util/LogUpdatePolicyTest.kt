package com.byd.dashcast.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogUpdatePolicyTest {

    @Test
    fun appendsWhenGenerationAndRetainedHeadAreStable() {
        assertTrue(LogUpdatePolicy.canAppend(2, 10, 14, 2, 10, 16))
    }

    @Test
    fun fullRefreshesAfterClear() {
        assertFalse(LogUpdatePolicy.canAppend(2, 10, 14, 3, 15, 14))
    }

    @Test
    fun fullRefreshesAfterAnyEviction() {
        assertFalse(LogUpdatePolicy.canAppend(2, 10, 14, 2, 11, 16))
    }

    @Test
    fun acceptsFirstAppendAfterAnEmptySnapshot() {
        assertTrue(LogUpdatePolicy.canAppend(4, 20, 19, 4, 20, 20))
    }

    @Test
    fun rejectsCursorAheadOfCurrentTail() {
        assertFalse(LogUpdatePolicy.canAppend(2, 10, 17, 2, 10, 16))
    }
}