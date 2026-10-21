package com.byd.dashcast.ui.diag

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AdasLogCaptureTest {
    private fun returned(capture: AdasLogCapture) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (capture.isBusy() && System.nanoTime() < end) Thread.sleep(2)
        assertFalse(capture.isBusy())
    }
    @Test fun `manual call records denial and summary never performs another read`() {
        val reads = AtomicInteger(); val releases = AtomicInteger()
        val capture = AdasLogCapture(release = { releases.incrementAndGet() })
        assertTrue(capture.start("daemonUid=2000; movement=UNKNOWN") { reads.incrementAndGet(); throw SecurityException("denied fixture") })
        returned(capture)
        repeat(3) { assertTrue(capture.summary().contains("SecurityException: denied fixture")) }
        assertEquals("FAILED", capture.snapshot()!!.outcome)
        assertEquals(1, reads.get()); assertEquals(1, releases.get())
    }
    @Test fun `cancel and timeout keep physical slot until late return without admitting retry`() {
        for (timeout in listOf(false, true)) {
            val entered = CountDownLatch(1); val unblock = CountDownLatch(1); val released = AtomicInteger()
            var now = 0L
            val capture = AdasLogCapture(timeoutMs = if (timeout) 30 else 8000, release = { released.incrementAndGet() }, monotonicMs = { now })
            try {
                assertTrue(capture.start("context") { entered.countDown(); unblock.await(); "schema=1; historical fixture" })
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                if (timeout) { now = 100; Thread.sleep(80) } else capture.cancel("Screen hidden")
                assertTrue(capture.isBusy()); assertEquals(0, released.get())
                assertFalse(capture.start("retry") { fail("retry admitted"); "" })
            } finally { unblock.countDown() }
            returned(capture)
            assertEquals(if (timeout) "LATE_TIMED_OUT" else "LATE_CANCELLED", capture.snapshot()!!.outcome)
            assertTrue(capture.summary().contains("historical fixture")); assertEquals(1, released.get())
        }
    }
    @Test fun `deadline is checked even if watchdog has not run and oversized payload fails bounded`() {
        var now = 0L
        val capture = AdasLogCapture(timeoutMs = 8000, monotonicMs = { now })
        assertTrue(capture.start("context") { now = 9000; "schema=1; historical" })
        returned(capture); assertEquals("LATE_TIMED_OUT", capture.snapshot()!!.outcome)
        assertTrue(capture.start("context") { "x".repeat(25 * 1024) })
        returned(capture); assertEquals("FAILED", capture.snapshot()!!.outcome)
        assertTrue(capture.snapshot()!!.result.isEmpty())
    }
}
