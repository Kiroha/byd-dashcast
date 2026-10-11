package com.byd.dashcast.media

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable

class MediaListenerLifecycleTest {
    private val scheduler = ManualMediaScheduler()
    private val transport = FakeMediaTransport()
    private val writer = ClusterMediaWriter(transport, scheduler)
    private val ownership = MediaOutputOwnership { writer }
    private var enabled = false
    private var opened = 0
    private var closed = 0
    private var changed: ((ClusterMediaData?) -> Unit)? = null
    private var failed: (() -> Unit)? = null
    private val data = ClusterMediaData("source", "Title", "", true)
    private val lifecycle = MediaListenerLifecycle({ enabled }, ownership) { c, f ->
        opened++; changed = c; failed = f; Closeable { closed++ }
    }

    @Test fun `default off connects and disconnects without media observers or writes`() {
        lifecycle.connected(); lifecycle.refresh(); lifecycle.disconnected(); lifecycle.close()
        scheduler.advance(1000)
        assertEquals(0, opened)
        assertTrue(transport.writes.isEmpty())
    }
    @Test fun `enabled setting alone cannot acquire sessions before system listener connection`() {
        enabled = true; lifecycle.refresh()
        assertEquals(0, opened)
        lifecycle.connected(); lifecycle.refresh()
        assertEquals(1, opened)
    }
    @Test fun `disable unregisters and rejects late callbacks clearing only owned output`() {
        enabled = true; lifecycle.connected(); changed!!(data); scheduler.advance(50)
        enabled = false; lifecycle.refresh(); changed!!(data); scheduler.advance(1000)
        assertEquals(1, closed)
        assertEquals(listOf("source:11", "text:  Title  ", "state:1", "text:    ", "state:2"), transport.writes)
    }
    @Test fun `disconnect invalidates queued source gap and reconnect creates a new observer`() {
        enabled = true; lifecycle.connected(); val oldCallback = changed!!
        changed!!(data); scheduler.advance()
        lifecycle.disconnected(); oldCallback(data); scheduler.advance(1000)
        assertEquals(listOf("source:11", "text:    ", "state:2"), transport.writes)
        lifecycle.connected(); oldCallback(data); scheduler.advance(1000)
        assertEquals(2, opened)
        assertEquals(3, transport.writes.size)
    }
    @Test fun `session failure releases output without restarting listener or another observer`() {
        enabled = true; lifecycle.connected(); changed!!(data); scheduler.advance(50)
        failed!!(); changed!!(data); scheduler.advance(1000)
        assertEquals(1, opened)
        assertEquals(1, closed)
        assertEquals("state:2", transport.writes.last())
    }
    @Test fun `synchronous authorization failure does not retain the failed observer`() {
        val immediateFailure = MediaListenerLifecycle({ true }, ownership) { _, fail ->
            fail(); Closeable { closed++ }
        }
        immediateFailure.connected()
        assertEquals(1, closed)
        immediateFailure.close()
        assertEquals(1, closed)
        scheduler.advance(1000)
        assertTrue(transport.writes.isEmpty())
    }
    @Test fun `bench and production cannot own media writer concurrently`() {
        val bench = Any()
        assertTrue(ownership.acquire(bench))
        enabled = true; lifecycle.connected()
        assertEquals(0, opened)
        ownership.release(bench) // Availability callback resumes production without another toggle.
        assertEquals(1, opened)
        assertFalse(ownership.acquire(bench))
    }

    @Test fun `destroyed or disabled waiting controller cannot acquire after bench release`() {
        val bench = Any(); assertTrue(ownership.acquire(bench))
        enabled = true; lifecycle.connected()
        enabled = false; lifecycle.refresh(); lifecycle.close()
        ownership.release(bench)
        assertEquals(0, opened)
    }
    @Test fun `old controller destruction cannot clear replacement owner`() {
        val old = Any(); val new = Any()
        assertTrue(ownership.acquire(old)); ownership.publish(old, data); scheduler.advance(50)
        ownership.release(old); assertTrue(ownership.acquire(new))
        ownership.publish(new, data.copy(title = "New")); ownership.release(old); scheduler.advance(50)
        assertEquals("text:  New  ", transport.writes[4])
        assertEquals(6, transport.writes.size)
    }
    @Test fun `destroyed lifecycle never restarts even if preference changes`() {
        enabled = true; lifecycle.connected(); lifecycle.close(); lifecycle.refresh(); lifecycle.connected()
        assertEquals(1, opened)
        assertEquals(1, closed)
    }
}
