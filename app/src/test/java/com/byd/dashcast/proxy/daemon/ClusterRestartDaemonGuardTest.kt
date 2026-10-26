package com.byd.dashcast.proxy.daemon

import com.byd.dashcast.clusterlive.ClusterRestartCompletion
import com.byd.dashcast.clusterlive.ClusterRestartLease
import com.byd.dashcast.clusterlive.ClusterThemeRetirement
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterRestartDaemonGuardTest {
    private fun field(name: String) = ClusterThemeLiveTest::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Test fun `confirmation and durable dispatch exclude other diagnostic mutations but keep recovery reachable`() {
        val initialized = field("initialized"); val oldInitialized = initialized.getBoolean(null)
        val pending = field("restartDispatchPending"); val oldPending = pending.getBoolean(null)
        val retirement = field("retirement"); val oldRetirement = retirement.get(null)
        val lease = field("restartLease").get(null) as ClusterRestartLease
        try {
            initialized.setBoolean(null, true); pending.setBoolean(null, false)
            lease.recovered(); lease.issue("a".repeat(32))
            retirement.set(null, ClusterThemeRetirement(".dashcast-retired-" + "b".repeat(32), 1, 10, true))
            for (operation in listOf(0, 4, 13, 27, 28, 29)) ClusterThemeLiveTest.checkRestartOperation(operation)
            for (operation in listOf(1, 2, 5, 7, 8, 9, 19, 20, 24, 25, 26))
                assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.checkRestartOperation(operation) }
            lease.recovered(); pending.setBoolean(null, true)
            assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.checkRestartOperation(24) }
            ClusterThemeLiveTest.checkRestartOperation(13)
            assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.resetRestartHistory() }
        } finally { lease.recovered(); pending.setBoolean(null, oldPending); initialized.setBoolean(null, oldInitialized); retirement.set(null, oldRetirement) }
    }
    @Test fun `next session clears only completed historical receipt and cannot discard pending intent`() {
        val pending = field("restartDispatchPending"); val oldPending = pending.getBoolean(null)
        val completed = field("restartCompleted"); val oldCompleted = completed.get(null)
        val lease = field("restartLease").get(null) as ClusterRestartLease
        try {
            pending.setBoolean(null, false); lease.recovered()
            completed.set(null, ClusterRestartCompletion("a".repeat(32), "11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222"))
            ClusterThemeLiveTest.resetRestartHistory(); assertNull(completed.get(null))
            pending.setBoolean(null, true)
            assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.resetRestartHistory() }
            assertTrue(pending.getBoolean(null))
        } finally { pending.setBoolean(null, oldPending); completed.set(null, oldCompleted) }
    }
}
