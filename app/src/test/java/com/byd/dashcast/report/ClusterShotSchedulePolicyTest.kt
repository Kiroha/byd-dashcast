package com.byd.dashcast.report

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterShotSchedulePolicyTest {
    @Test
    fun `captures only an active due projection`() {
        assertTrue(ClusterShotSchedulePolicy.shouldCapture(2, 20_000, 0, 15_000))
        assertFalse(ClusterShotSchedulePolicy.shouldCapture(-1, 20_000, 0, 15_000))
        assertFalse(ClusterShotSchedulePolicy.shouldCapture(2, 20_000, 10_000, 15_000))
    }

    @Test
    fun `recent daemon prune suppresses active app prune`() {
        assertFalse(ClusterShotSchedulePolicy.shouldAppPrune(2, 60_000, 0, 45_000, 30_000))
    }

    @Test
    fun `failed active capture keeps app prune fallback`() {
        assertTrue(ClusterShotSchedulePolicy.shouldAppPrune(2, 60_000, 0, 0, 30_000))
    }

    @Test
    fun `app keeps pruning after projection stops`() {
        assertTrue(ClusterShotSchedulePolicy.shouldAppPrune(-1, 60_000, 0, 59_000, 30_000))
        assertFalse(ClusterShotSchedulePolicy.shouldAppPrune(-1, 60_000, 45_000, 0, 30_000))
    }
}