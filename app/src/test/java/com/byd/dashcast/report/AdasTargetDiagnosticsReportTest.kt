package com.byd.dashcast.report

import com.byd.dashcast.proxy.AdasTargetProperties
import com.byd.dashcast.ui.diag.AdasTargetDiagnostics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AdasTargetDiagnosticsReportTest {
    @Test fun `normal and fallback reports include retained raw and errors without another OEM read`() {
        val reads = AtomicInteger()
        assertTrue(AdasTargetDiagnostics.capture.start(null, "runtimeApi=29; daemonUid=2000; hardware=unverified") {
            if (reads.incrementAndGet() == 2) throw SecurityException("OEM denied fixture")
            -10011
        })
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (AdasTargetDiagnostics.capture.isBusy() && System.nanoTime() < end) Thread.sleep(2)
        assertFalse(AdasTargetDiagnostics.capture.isBusy())
        assertEquals(33, reads.get())
        val context = RuntimeEnvironment.getApplication()
        for (fallback in listOf(false, true)) {
            val output = File(context.cacheDir, if (fallback) "adas-fallback-directory" else "adas-normal.txt")
            if (fallback) assertTrue(output.mkdir())
            var produced: File? = null
            BugReportCapture.finish(context, output, "Manual fixture", object : BugReportCapture.Callback {
                override fun onReady(file: File) { produced = file }
                override fun onError(message: String, partial: File?) { produced = partial }
            }, null, if (fallback) null else "Synthetic shell body")
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertNotNull(produced)
            val body = produced!!.readText()
            assertTrue(body.contains("ADAS TARGET DIAGNOSTIC (historical manual cache snapshot)"))
            assertTrue(body.contains("raw=-10011"))
            assertTrue(body.contains("SecurityException: OEM denied fixture"))
            assertTrue(body.contains("daemonUid=2000"))
            assertTrue(body.contains("freshness"))
            assertEquals(33, reads.get())
            AdasTargetProperties.allowed.forEach { assertTrue(body.contains(AdasTargetProperties.hex(it))) }
            if (fallback) assertTrue(body.contains("report body build failed"))
        }
    }
}
