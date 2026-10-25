package com.byd.dashcast.proxy.daemon

import android.os.SystemClock
import com.byd.dashcast.clusterlive.ClusterThemePolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterDashboardProbeLifecycleTest {
    private class OwnedReadProcess : Process() {
        var alive = true
        var destroyed = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit) = !alive
        override fun exitValue(): Int { check(!alive); return 0 }
        override fun isAlive() = alive
        override fun destroy() { destroyed = true; alive = false }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    @Test fun `revoked display probe stops its owned child without waiting for the eight second watchdog`() {
        val process = OwnedReadProcess(); var validChecks = 0
        assertThrows(IllegalStateException::class.java) {
            ClusterThemeQueryRunner.awaitDisplayProbe(process, SystemClock.elapsedRealtime() + 8000) { ++validChecks < 2 }
        }
        assertTrue(process.destroyed); assertFalse(process.alive); assertEquals(2, validChecks)
    }

    @Test fun `unreaped read-only child prevents a second probe from spawning`() {
        val field = ClusterThemeQueryRunner::class.java.getDeclaredField("outstandingDisplayProbe").apply { isAccessible = true }
        val process = OwnedReadProcess(); field.set(null, process)
        try {
            var admitted = 0
            val result = ClusterThemeQueryRunner.dashboardDisplay(ClusterThemePolicy.PREFIX + "a".repeat(32) + ".rcc", 2000,
                { true }, { admitted++ })
            assertFalse(result.reaped); assertEquals("UNAVAILABLE", result.receipt.getString("state")); assertEquals(0, admitted)
            assertFalse(process.destroyed)
        } finally { field.set(null, null) }
    }
}
