package com.byd.dashcast.media

import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

class MediaRecoveryTest {
    private val scheduler = ManualMediaScheduler()
    private val transport = FakeMediaTransport()
    private val ownership = MediaOutputOwnership { ClusterMediaWriter(transport, scheduler) }
    private val diagnostics = MediaDiagnosticStore()
    private var enabled = true
    private var opens = 0
    private var closes = 0
    private var fail: (() -> Unit)? = null
    private var changed: ((ClusterMediaData?) -> Unit)? = null
    private var synchronous = false
    private var throwing = false
    private val lifecycle = MediaListenerLifecycle({ enabled }, ownership,
        retryScheduler = scheduler, diagnostics = diagnostics) { c, f ->
        opens++; fail = f; changed = c
        if (throwing) throw SecurityException("private title secret")
        if (synchronous) f()
        Closeable { closes++ }
    }

    @Test fun `synchronous failure retries at capped exponential intervals without duplicate observers`() {
        synchronous = true; lifecycle.connected()
        assertEquals(1, opens); assertEquals(1, closes)
        lifecycle.refresh(); assertEquals(1, opens)
        for (delay in listOf(1000L, 2000L, 5000L, 15000L, 30000L, 30000L)) {
            val before = opens; scheduler.advance(delay - 1); assertEquals(before, opens)
            scheduler.advance(1); assertEquals(before + 1, opens); assertEquals(opens, closes)
        }
        lifecycle.close(); scheduler.advance(60000); assertEquals(7, opens)
    }

    @Test fun `construction exceptions retry without retaining exception content`() {
        throwing = true; lifecycle.connected()
        assertTrue(diagnostics.summary(true).contains("authorization=DENIED"))
        assertFalse(diagnostics.summary(true).contains("secret"))
        scheduler.advance(1000); assertEquals(2, opens)
        throwing = false; scheduler.advance(2000); assertEquals(3, opens)
    }

    @Test fun `successful empty collection resets backoff and late failure cannot stop replacement`() {
        lifecycle.connected(); val oldFailure = fail!!; val oldChanged = changed!!
        fail!!(); scheduler.advance(1000); fail!!(); scheduler.advance(2000)
        changed!!(null); fail!!()
        scheduler.advance(999); assertEquals(3, opens)
        scheduler.advance(1); assertEquals(4, opens)
        oldFailure(); oldChanged(ClusterMediaData("private source", "private title", "artist", true))
        scheduler.advance(50); assertTrue(transport.writes.isEmpty()); assertEquals(4, opens)
    }

    @Test fun `disable disconnect and close cancel retries and reject timers`() {
        lifecycle.connected(); fail!!(); enabled = false; lifecycle.refresh()
        scheduler.advance(30000); assertEquals(1, opens)
        enabled = true; lifecycle.refresh(); fail!!(); lifecycle.disconnected()
        scheduler.advance(30000); assertEquals(2, opens)
        lifecycle.connected(); fail!!(); lifecycle.close()
        scheduler.advance(30000); assertEquals(3, opens)
    }

    @Test fun `bench ownership during retry wait does not bypass backoff`() {
        lifecycle.connected(); fail!!()
        val bench = Any(); assertTrue(ownership.acquire(bench))
        scheduler.advance(1000); assertEquals(1, opens)
        ownership.release(bench); assertEquals(2, opens)
        fail!!(); assertTrue(ownership.acquire(bench)); ownership.release(bench)
        lifecycle.refresh(); scheduler.advance(1999); assertEquals(2, opens)
        scheduler.advance(1); assertEquals(3, opens)
    }

    @Test fun `diagnostic collection is bounded and rejects obsolete generations`() {
        val old = diagnostics.begin(); val current = diagnostics.begin()
        diagnostics.collection(old, 20, 20, true)
        assertTrue(diagnostics.summary(true).contains("sessions=0"))
        diagnostics.collection(current, 10000, 10000, true)
        val summary = diagnostics.summary(true)
        assertTrue(summary.contains("sessions=32 usableSessions=32 playingSessions=0 selected=true"))
        diagnostics.stop(); diagnostics.collection(current, 3, 3, true)
        assertTrue(diagnostics.summary(false).contains("sessions=0"))
    }
    @Test fun `SDK rejection diagnostics survive cleanup and collection never retains content`() {
        val writer = ClusterMediaWriter(transport, scheduler, report = diagnostics::result,
            diagnostic = diagnostics::output)
        transport.rejectNextText = true
        writer.publish(ClusterMediaData("private source", "private title", "private artist", true))
        scheduler.advance(50)
        val summary = diagnostics.summary(true)
        assertTrue(summary.contains("outputResult=UNAVAILABLE"))
        assertTrue(summary.contains("lastRejectedStage=TEXT lastRejectedSdkReturn=-1"))
        assertFalse(summary.contains("private"))
        assertFalse(summary.contains("artist"))
    }

    @Test fun `already dequeued old retry cannot restart or consume replacement retry`() {
        val tasks = mutableListOf<() -> Unit>()
        val racingScheduler = object : MediaScheduler {
            override fun schedule(delayMs: Long, action: () -> Unit): MediaCancellation {
                tasks += action
                return MediaCancellation { /* Simulate cancellation after the runnable was dequeued. */ }
            }
            override fun close() = Unit
        }
        var starts = 0
        var failure: (() -> Unit)? = null
        val controller = MediaListenerLifecycle({ true }, ownership,
            retryScheduler = racingScheduler) { _, f -> starts++; failure = f; Closeable {} }
        controller.connected(); failure!!(); val old = tasks.single()
        controller.disconnected(); controller.connected(); failure!!()
        old(); assertEquals(2, starts)
        tasks.last()(); assertEquals(3, starts)
        controller.close()
    }

    @Test fun `timer observing disabled preference releases retry even before preference callback`() {
        lifecycle.connected(); fail!!(); enabled = false
        scheduler.advance(1000)
        enabled = true; lifecycle.refresh()
        assertEquals(2, opens)
        lifecycle.close(); lifecycle.connected()
        assertTrue(diagnostics.summary(true).contains("listenerConnected=false"))
    }

}
