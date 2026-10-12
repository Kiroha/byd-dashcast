package com.byd.dashcast.media

import org.junit.Assert.*
import org.junit.Test

internal class ManualMediaScheduler : MediaScheduler {
    data class Task(val at: Long, val action: () -> Unit)
    var now = 0L
    private val tasks = mutableListOf<Task>()
    val pendingCount get() = tasks.size
    override fun schedule(delayMs: Long, action: () -> Unit): MediaCancellation {
        val task = Task(now + delayMs, action)
        tasks += task
        return MediaCancellation { tasks.remove(task) }
    }
    fun advance(ms: Long = 0) {
        val until = now + ms
        while (true) {
            val task = tasks.filter { it.at <= until }.minByOrNull { it.at } ?: break
            tasks.remove(task)
            now = task.at
            task.action()
        }
        now = until
    }
    override fun close() { tasks.clear() }
}

internal class FakeMediaTransport : ClusterMediaTransport {
    var binder: Any? = Any()
    val writes = mutableListOf<String>()
    var rejectSource = false
    var rejectNextText = false
    var rejectNextState = false
    var throwNext = false
    var throwNextText = false
    var afterWrite: (() -> Unit)? = null
    override fun connection() = binder
    override fun isCurrent(connection: Any) = connection === binder
    override fun integer(connection: Any, property: Int, value: Int): Int {
        assertTrue(isCurrent(connection))
        val name = if (property == ClusterMediaProperties.SOURCE) "source" else {
            assertEquals(ClusterMediaProperties.STATE, property); "state"
        }
        writes += "$name:$value"
        afterWrite?.invoke()
        if (throwNext) { throwNext = false; throw IllegalStateException("transport") }
        if (name == "source" && rejectSource) return -1
        if (name == "state" && rejectNextState) { rejectNextState = false; return -1 }
        return 0
    }
    override fun bytes(connection: Any, property: Int, value: ByteArray): Int {
        assertTrue(isCurrent(connection))
        assertEquals(ClusterMediaProperties.TEXT, property)
        assertTrue(value.size <= 254 && value.size % 2 == 0)
        writes += "text:" + value.toString(Charsets.UTF_16LE)
        afterWrite?.invoke()
        if (throwNextText) { throwNextText = false; throw IllegalStateException("transport") }
        if (rejectNextText) { rejectNextText = false; return -1 }
        return 0
    }
}

class ClusterMediaWriterTest {
    private val scheduler = ManualMediaScheduler()
    private val transport = FakeMediaTransport()
    private val reports = mutableListOf<MediaWriteResult>()
    private val writer = ClusterMediaWriter(transport, scheduler, { scheduler.now }, { reports += it })
    private val source = Any()
    private fun track(title: String = "Track", playing: Boolean = true) = ClusterMediaData(source, title, "Artist", playing)
    private fun send(data: ClusterMediaData = track()) { writer.publish(data); scheduler.advance(50) }
    private fun sendProbe(data: ClusterMediaData = track()) {
        writer.publish(data, MediaCleanupMode.STOP_PROBE); scheduler.advance(50)
    }
    private val empty = "text:    "

    @Test fun `text and state follow accepted source after fifty milliseconds`() {
        writer.publish(track())
        scheduler.advance()
        assertEquals(listOf("source:11"), transport.writes)
        scheduler.advance(49)
        assertEquals(1, transport.writes.size)
        scheduler.advance(1)
        assertEquals(listOf("source:11", "text:  Artist - Track  ", "state:1"), transport.writes)
    }

    @Test fun `unowned stop performs no SDK calls`() {
        writer.publish(null)
        scheduler.advance(1000)
        assertTrue(transport.writes.isEmpty())
    }

    @Test fun `duplicate delivered callbacks do not write again`() {
        send()
        repeat(100) { writer.publish(track()) }
        scheduler.advance(1000)
        assertEquals(3, transport.writes.size)
    }

    @Test fun `bursts coalesce to one pending latest value`() {
        repeat(1000) { writer.publish(track("$it")) }
        assertEquals(1, scheduler.pendingCount)
        scheduler.advance(50)
        assertEquals(listOf("source:11", "text:  Artist - 999  ", "state:1"), transport.writes)
    }

    @Test fun `disable during source gap invalidates title and cleans partial acquisition`() {
        writer.publish(track())
        scheduler.advance()
        writer.publish(null)
        scheduler.advance(1000)
        assertEquals(listOf("source:11", empty, "state:2"), transport.writes)
    }

    @Test fun `new title during gap replaces obsolete suffix`() {
        writer.publish(track("Old")); scheduler.advance()
        writer.publish(track("New")); scheduler.advance(50)
        assertEquals(listOf("source:11", "source:11", "text:  Artist - New  ", "state:1"), transport.writes)
    }

    @Test fun `daemon replacement cancels suffix and never clears replacement`() {
        writer.publish(track()); scheduler.advance()
        transport.binder = Any()
        scheduler.advance(50)
        writer.publish(null); scheduler.advance()
        assertEquals(listOf("source:11"), transport.writes)
        assertTrue(reports.contains(MediaWriteResult.UNAVAILABLE))
    }

    @Test fun `loss clears owned output without writing source`() {
        send(); transport.writes.clear()
        writer.publish(null); scheduler.advance()
        assertEquals(listOf(empty, "state:2"), transport.writes)
        writer.publish(null); scheduler.advance()
        assertEquals(2, transport.writes.size)
    }

    @Test fun `source rejection aborts text and playback state`() {
        transport.rejectSource = true
        send()
        assertEquals(listOf("source:11"), transport.writes)
        assertEquals(MediaWriteResult.UNAVAILABLE, reports.last())
    }

    @Test fun `initial text rejection clears partial source acquisition exactly once`() {
        transport.rejectNextText = true
        send()
        assertEquals(listOf("source:11", "text:  Artist - Track  ", empty, "state:2"), transport.writes)
        writer.publish(null); scheduler.advance(10000)
        assertEquals(4, transport.writes.size)
    }

    @Test fun `initial state rejection cleans accepted text instead of leaving stale state`() {
        transport.rejectNextState = true
        send()
        assertEquals(listOf("source:11", "text:  Artist - Track  ", "state:1", empty, "state:2"), transport.writes)
    }

    @Test fun `failed new title after old accepted title clears owned text`() {
        send(track("A")); transport.writes.clear()
        transport.rejectNextText = true
        send(track("B"))
        assertEquals(listOf("source:11", "text:  Artist - B  ", empty, "state:2"), transport.writes)
    }

    @Test fun `failed new state after old accepted title clears new text`() {
        send(track("A")); transport.writes.clear()
        transport.rejectNextState = true
        send(track("B", false))
        assertEquals(listOf("source:11", "text:  Artist - B  ", "state:2", empty, "state:2"), transport.writes)
    }

    @Test fun `failed cleanup aborts pause and is observable without metadata`() {
        send(); transport.writes.clear()
        transport.rejectNextText = true
        writer.publish(null); scheduler.advance(10000)
        assertEquals(listOf(empty), transport.writes)
        assertEquals(MediaWriteResult.CLEAR_FAILED, reports.last())
    }

    @Test fun `replacement during accepted clear text suppresses pause and reports unconfirmed cleanup`() {
        send(); transport.writes.clear()
        transport.afterWrite = { transport.afterWrite = null; transport.binder = Any() }
        writer.publish(null); scheduler.advance(1000)
        assertEquals(listOf(empty), transport.writes)
        assertEquals(MediaWriteResult.CLEAR_FAILED, reports.last())
    }

    @Test fun `a stopped update cannot set state after an in-flight text write`() {
        writer.publish(track()); scheduler.advance()
        transport.afterWrite = { transport.afterWrite = null; writer.publish(null) }
        scheduler.advance(50)
        assertEquals(listOf("source:11", "text:  Artist - Track  ", empty, "state:2"), transport.writes)
    }

    @Test fun `a source stop race still tracks accepted partial ownership for cleanup`() {
        transport.afterWrite = { transport.afterWrite = null; writer.publish(null) }
        send()
        assertEquals(listOf("source:11", empty, "state:2"), transport.writes)
    }

    @Test fun `fresh callbacks retry unavailable transport after cooldown without a replay timer`() {
        transport.binder = null
        send()
        transport.binder = Any()
        writer.publish(track()); scheduler.advance(50)
        assertTrue(transport.writes.isEmpty())
        scheduler.advance(5000)
        assertTrue(transport.writes.isEmpty())
        writer.publish(track()); scheduler.advance(50)
        assertEquals(3, transport.writes.size)
    }

    @Test fun `faulty result callback cannot stall later updates`() {
        writer.publish(track()) { throw IllegalStateException() }
        scheduler.advance(50)
        send(track("New"))
        assertEquals("text:  Artist - New  ", transport.writes[4])
    }

    @Test fun `an exception does not restart daemon and subsequent different callback works`() {
        transport.throwNext = true
        send()
        assertEquals(listOf("source:11"), transport.writes)
        send(track("New"))
        assertEquals(4, transport.writes.size)
    }

    @Test fun `new output supersedes a queued clear and old cleanup never erases it`() {
        send(); transport.writes.clear()
        writer.publish(null)
        writer.publish(track("New")); scheduler.advance(50)
        assertEquals(listOf("source:11", "text:  Artist - New  ", "state:1"), transport.writes)
    }

    @Test fun `normal pause and production cleanup keep paused state instead of stop`() {
        send(track(playing = false))
        assertEquals("state:2", transport.writes.last())
        writer.publish(null); scheduler.advance(10000)
        assertEquals(listOf("source:11", "text:  Artist - Track  ", "state:2", empty, "state:2"), transport.writes)
        assertFalse(transport.writes.contains("state:3"))
    }

    @Test fun `probe cleanup uses its acquired mode even when stop caller uses production default`() {
        sendProbe(); transport.writes.clear()
        writer.publish(null); scheduler.advance(10000)
        assertEquals(listOf(empty, "state:3"), transport.writes)
        assertEquals(MediaWriteResult.CLEARED, reports.last())
        writer.publish(null); scheduler.advance(10000)
        assertEquals(2, transport.writes.size)
    }

    @Test fun `probe stop caller cannot change production owned paused cleanup`() {
        send(); transport.writes.clear()
        writer.publish(null, MediaCleanupMode.STOP_PROBE); scheduler.advance(1000)
        assertEquals(listOf(empty, "state:2"), transport.writes)
        assertFalse(transport.writes.contains("state:3"))
    }

    @Test fun `failed cleanup remains observable at release until a new source is accepted`() {
        sendProbe(); transport.rejectNextState = true
        writer.publish(null, MediaCleanupMode.STOP_PROBE); scheduler.advance()
        var released: MediaWriteResult? = null
        val before = transport.writes.size
        writer.publish(null, MediaCleanupMode.STOP_PROBE) { released = it }; scheduler.advance(10000)
        assertEquals(MediaWriteResult.CLEAR_FAILED, released)
        assertEquals(before, transport.writes.size)
        send()
        writer.publish(null) { released = it }; scheduler.advance()
        assertEquals(MediaWriteResult.CLEARED, released)
        writer.publish(null) { released = it }; scheduler.advance()
        assertEquals(MediaWriteResult.NOTHING_TO_CLEAR, released)
    }

    @Test fun `unowned probe stop sends nothing and never selects a source`() {
        writer.publish(null, MediaCleanupMode.STOP_PROBE); scheduler.advance(10000)
        assertTrue(transport.writes.isEmpty())
        assertEquals(MediaWriteResult.NOTHING_TO_CLEAR, reports.last())
    }

    @Test fun `probe clear text rejection still attempts stop once and remains failed`() {
        sendProbe(); transport.writes.clear(); transport.rejectNextText = true
        writer.publish(null, MediaCleanupMode.STOP_PROBE); scheduler.advance(10000)
        assertEquals(listOf(empty, "state:3"), transport.writes)
        assertEquals(MediaWriteResult.CLEAR_FAILED, reports.last())
        writer.publish(null, MediaCleanupMode.STOP_PROBE); scheduler.advance(10000)
        assertEquals(2, transport.writes.size)
    }

    @Test fun `probe clear text exception still attempts stop once against current binder`() {
        sendProbe(); transport.writes.clear(); transport.throwNextText = true
        writer.publish(null, MediaCleanupMode.STOP_PROBE); scheduler.advance(10000)
        assertEquals(listOf(empty, "state:3"), transport.writes)
        assertEquals(MediaWriteResult.CLEAR_FAILED, reports.last())
    }

    @Test fun `probe rejected stop reports failed cleanup without retry`() {
        sendProbe(); transport.writes.clear(); transport.rejectNextState = true
        writer.publish(null, MediaCleanupMode.STOP_PROBE); scheduler.advance(10000)
        assertEquals(listOf(empty, "state:3"), transport.writes)
        assertEquals(MediaWriteResult.CLEAR_FAILED, reports.last())
    }

    @Test fun `probe stop exception reports failed cleanup without retry or source restore`() {
        sendProbe(); transport.writes.clear(); transport.throwNext = true
        writer.publish(null, MediaCleanupMode.STOP_PROBE); scheduler.advance(10000)
        assertEquals(listOf(empty, "state:3"), transport.writes)
        assertEquals(MediaWriteResult.CLEAR_FAILED, reports.last())
    }

    @Test fun `probe lost binder between clear operations never stops replacement`() {
        sendProbe(); transport.writes.clear()
        transport.afterWrite = { transport.afterWrite = null; transport.binder = Any() }
        writer.publish(null, MediaCleanupMode.STOP_PROBE); scheduler.advance(10000)
        assertEquals(listOf(empty), transport.writes)
        assertEquals(MediaWriteResult.CLEAR_FAILED, reports.last())
    }

    @Test fun `a second stop superseding probe text clear reports uncertainty without replay`() {
        sendProbe(); transport.writes.clear()
        var secondStop: MediaWriteResult? = null
        transport.afterWrite = {
            transport.afterWrite = null
            writer.publish(null, MediaCleanupMode.STOP_PROBE) { secondStop = it }
        }
        writer.publish(null, MediaCleanupMode.STOP_PROBE); scheduler.advance(10000)
        assertEquals(listOf(empty), transport.writes)
        assertEquals(MediaWriteResult.CLEAR_FAILED, secondStop)
    }

    @Test fun `new automatic output during probe text clear invalidates the old stop suffix`() {
        sendProbe(); transport.writes.clear()
        transport.afterWrite = { transport.afterWrite = null; writer.publish(track("New")) }
        writer.publish(null, MediaCleanupMode.STOP_PROBE); scheduler.advance(1000)
        assertEquals(listOf(empty, "source:11", "text:  Artist - New  ", "state:1"), transport.writes)
        writer.publish(null); scheduler.advance(1000)
        assertEquals(listOf(empty, "state:2"), transport.writes.takeLast(2))
        assertFalse(transport.writes.contains("state:3"))
    }

    @Test fun `automatic acquisition replaces probe cleanup mode for identical metadata`() {
        sendProbe(); transport.writes.clear()
        writer.publish(track()); scheduler.advance(50)
        assertEquals(listOf("source:11", "text:  Artist - Track  ", "state:1"), transport.writes)
        writer.publish(null); scheduler.advance(1000)
        assertEquals(listOf(empty, "state:2"), transport.writes.takeLast(2))
    }

    @Test fun `probe stop during source gap clears partial acquisition without sending title`() {
        writer.publish(track(), MediaCleanupMode.STOP_PROBE); scheduler.advance()
        writer.publish(null, MediaCleanupMode.STOP_PROBE); scheduler.advance(1000)
        assertEquals(listOf("source:11", empty, "state:3"), transport.writes)
    }

    @Test fun `probe initial text failure cleans partial acquisition with stop not pause`() {
        transport.rejectNextText = true
        sendProbe(); scheduler.advance(1000)
        assertEquals(listOf("source:11", "text:  Artist - Track  ", empty, "state:3"), transport.writes)
        assertEquals(MediaWriteResult.UNAVAILABLE, reports.last())
    }

    @Test fun `probe initial state failure stops its accepted text without a new source`() {
        transport.rejectNextState = true
        sendProbe(); scheduler.advance(1000)
        assertEquals(listOf("source:11", "text:  Artist - Track  ", "state:1", empty, "state:3"), transport.writes)
        assertEquals(MediaWriteResult.UNAVAILABLE, reports.last())
    }
}
