package com.byd.dashcast.clusterlive

import org.junit.Assert.*
import org.junit.Test

class ClusterDashboardIntervalsTest {
    @Test fun `native interval spans open close restore and failed attempts`() {
        var now = 0L
        val intervals = ClusterDashboardIntervals({ now }, { now += it })
        val sent = mutableListOf<Pair<Int, Long>>()
        for (command in ClusterDashboardProfile.openCommands + ClusterDashboardProfile.closeCommands) {
            intervals.awaitNative { true }; sent += command to now
            intervals.nativeFinished()
        }
        assertEquals(listOf(31, 16, 35, 18, 0, 31), sent.map { it.first })
        assertEquals(listOf(1200L, 2400L, 3600L, 4800L, 6000L, 7200L), sent.map { it.second })
        // An uncertain helper return also reserves the next interval.
        now += 80; intervals.nativeFinished(); intervals.awaitNative { true }
        assertEquals(8480L, now)
    }
    @Test fun `RCC interval lives outside short helper and cancellation prevents admission`() {
        var now = 0L; var valid = true
        val intervals = ClusterDashboardIntervals({ now }, { now += it })
        intervals.awaitRcc { valid }; assertEquals(15500L, now)
        intervals.rccFinished(); assertEquals(15500L, intervals.rccRemaining())
        valid = false
        assertThrows(IllegalStateException::class.java) { intervals.awaitRcc { valid } }
        assertEquals(15500L, now)
        valid = true; intervals.awaitRcc { valid }; assertEquals(31000L, now)
    }
    @Test fun `unknown small ambiguous geometry and unapproved native verbs are refused`() {
        assertTrue(ClusterDashboardProfile.permitsGeometry(1920, 720, false))
        for ((w, h) in listOf(0 to 0, 1280 to 480, 1920 to 0, 1920 to 1080))
            assertFalse(ClusterDashboardProfile.permitsGeometry(w, h, false))
        assertFalse(ClusterDashboardProfile.permitsGeometry(1920, 720, true))
        for (command in listOf(-1, 1, 13, 29, 30, 88, 89)) assertFalse(ClusterDashboardProfile.permitsCommand(command))
        assertEquals(43, ClusterThemeVariant.FULL_DASHBOARD_21F7735C.minimumProtocol)
    }
}
