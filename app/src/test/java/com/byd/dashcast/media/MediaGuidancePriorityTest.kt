package com.byd.dashcast.media

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Tests real ownership/lifecycle/writer together, including the asynchronous resume barrier. */
class MediaGuidancePriorityTest {
    private val scheduler = ManualMediaScheduler()
    private val transport = FakeMediaTransport()
    private val writer = ClusterMediaWriter(transport, scheduler)
    private val ownership = MediaOutputOwnership { writer }
    private var enabled = true
    private var opened = 0
    private var closed = 0
    private lateinit var changed: (ClusterMediaData?) -> Unit
    private val snapshots = mutableListOf<(ClusterMediaData?) -> Unit>()
    private val lifecycle = MediaListenerLifecycle({ enabled }, ownership,
        requestSnapshot = { _, callback -> snapshots += callback }) { callback, _ ->
        opened++; changed = callback; Closeable { closed++ }
    }
    private val original = ClusterMediaData("local", "Original", "", true)
    private val fresh = original.copy(title = "Current")

    @Test fun `guidance cancels the fifty millisecond suffix and retains collection`() {
        lifecycle.connected(); changed(original); scheduler.advance()
        ownership.clusterGuidance(true); changed(fresh); scheduler.advance(1000)
        assertEquals(listOf("source:11", "text:    ", "state:2"), transport.writes)
        assertEquals(1, opened)
        assertEquals(0, closed)
        lifecycle.close()
    }

    @Test fun `release waits for fresh snapshot while rejecting queued metadata`() {
        lifecycle.connected(); changed(original); scheduler.advance(50)
        ownership.clusterGuidance(true); scheduler.advance()
        val count = transport.writes.size
        ownership.clusterGuidance(false)
        changed(original); changed(fresh); scheduler.advance(1000)
        assertEquals(count, transport.writes.size)
        assertEquals(1, snapshots.size)
        snapshots.last()(fresh); scheduler.advance(50)
        assertEquals("text:  Current  ", transport.writes[transport.writes.lastIndex - 1])
        assertEquals("state:1", transport.writes.last())
        assertEquals(1, opened)
        lifecycle.close()
    }

    @Test fun `fresh snapshot with no session cannot revive the old title`() {
        lifecycle.connected(); changed(original); scheduler.advance(50)
        ownership.clusterGuidance(true); scheduler.advance()
        val count = transport.writes.size
        ownership.clusterGuidance(false); snapshots.last()(null); scheduler.advance(1000)
        assertEquals(count, transport.writes.size)
        lifecycle.close()
    }

    @Test fun `resume ticket from an earlier navigation session cannot reopen presentation`() {
        lifecycle.connected(); ownership.clusterGuidance(true); ownership.clusterGuidance(false)
        val old = snapshots.last()
        ownership.clusterGuidance(true); old(original); scheduler.advance(1000)
        assertTrue(transport.writes.isEmpty())
        ownership.clusterGuidance(false); old(original); scheduler.advance(1000)
        assertTrue(transport.writes.isEmpty())
        snapshots.last()(fresh); scheduler.advance(50)
        assertEquals(listOf("source:11", "text:  Current  ", "state:1"), transport.writes)
        lifecycle.close()
    }

    @Test fun `disabled disconnected and destroyed observers reject resume callbacks`() {
        for (stop in listOf<() -> Unit>({ enabled = false; lifecycle.refresh() },
            { lifecycle.disconnected() }, { lifecycle.close() })) {
            enabled = true; lifecycle.connected()
            ownership.clusterGuidance(true); ownership.clusterGuidance(false)
            val callback = snapshots.last()
            stop(); callback(fresh); scheduler.advance(1000)
            assertTrue(transport.writes.isEmpty())
        }
    }

    @Test fun `startup during guidance observes sessions but cannot publish or run the bench`() {
        ownership.clusterGuidance(true); lifecycle.connected(); changed(original); scheduler.advance(1000)
        assertEquals(1, opened)
        assertTrue(transport.writes.isEmpty())
        assertFalse(ownership.acquire(Any()))
        ownership.clusterGuidance(false); snapshots.last()(fresh); scheduler.advance(50)
        assertEquals("text:  Current  ", transport.writes[1])
        lifecycle.close()
    }

    @Test fun `guidance never initializes a writer while music has no owner`() {
        var created = 0
        val idle = MediaOutputOwnership { created++; writer }
        idle.clusterGuidance(true); idle.clusterGuidance(true); idle.clusterGuidance(false)
        assertEquals(0, created)
        assertTrue(transport.writes.isEmpty())
        lifecycle.close()
    }

    @Test fun `unblocking during observer creation still requests a fresh snapshot`() {
        ownership.clusterGuidance(true)
        val constructing = MediaListenerLifecycle({ true }, ownership,
            requestSnapshot = { _, callback -> snapshots += callback }) { callback, _ ->
            ownership.clusterGuidance(false)
            callback(original)
            Closeable { }
        }
        constructing.connected(); scheduler.advance(1000)
        assertTrue(transport.writes.isEmpty())
        assertEquals(1, snapshots.size)
        snapshots.last()(fresh); scheduler.advance(50)
        assertEquals("text:  Current  ", transport.writes[1])
        constructing.close(); lifecycle.close()
    }

    @Test fun `cleanup cannot cross to a replacement daemon during guidance`() {
        lifecycle.connected(); changed(original); scheduler.advance(50)
        transport.binder = Any()
        val count = transport.writes.size
        ownership.clusterGuidance(true); scheduler.advance(1000)
        assertEquals(count, transport.writes.size)
        ownership.clusterGuidance(false); snapshots.last()(fresh); scheduler.advance(50)
        assertEquals(count + 3, transport.writes.size)
        lifecycle.close()
    }

    @Test fun `navigation priority never waits behind an in flight media transaction`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gated = CountDownLatch(1)
        val cleared = CountDownLatch(1)
        val worker = ExecutorMediaScheduler()
        val blocked = FakeMediaTransport().apply {
            afterWrite = {
                if (writes.last() == "source:11") {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
                if (writes.last() == "state:2") cleared.countDown()
            }
        }
        val serial = ClusterMediaWriter(blocked, worker)
        val gate = MediaOutputOwnership { serial }
        val token = Any()
        var navigation: Thread? = null
        try {
            assertTrue(gate.acquire(token))
            gate.publish(token, original)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            navigation = Thread { gate.clusterGuidance(true); gated.countDown() }.apply { start() }
            assertTrue("Navigation must not wait for the blocked media Binder", gated.await(1, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(cleared.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("source:11", "text:    ", "state:2"), blocked.writes)
        } finally {
            release.countDown()
            navigation?.join(3000)
            worker.close()
            lifecycle.close()
        }
    }
}
