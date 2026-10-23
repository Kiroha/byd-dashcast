package com.byd.dashcast.clusterlive

import com.google.flatbuffers.FlatBufferBuilder
import org.junit.Assert.*
import org.junit.Test

class ClusterThemeIviExchangeTest {
    private val name = ClusterThemePolicy.PREFIX + "a".repeat(32) + ".rcc"
    private fun selection(source: Int = 1, path: String = "default", version: String = ClusterThemeProtocol.UI_VERSION): ByteArray {
        val b = FlatBufferBuilder(512)
        val ui = b.createString(version); val p = b.createString(path); val resource = b.createString("default")
        b.startTable(13); b.addInt(0, 1001, 0); b.addInt(1, 4, 0); b.addInt(2, 1, 0); b.addInt(5, source, 0)
        b.addOffset(7, ui, 0); b.addOffset(9, p, 0); b.addOffset(11, resource, 0)
        b.finish(b.endTable()); return b.sizedByteArray()
    }
    private fun ack(path: String = ClusterThemePolicy.HOST_DIRECTORY + name, kind: Int = 2): ByteArray {
        val b = FlatBufferBuilder(512); val p = b.createString(path)
        b.startTable(13); b.addInt(0, 1005, 0); b.addInt(2, kind, 0); b.addOffset(7, p, 0)
        b.finish(b.endTable()); return b.sizedByteArray()
    }
    private inner class Fixture : ClusterThemeIviExchange.Transport {
        var time = 0L
        var receiver: ((Int, ByteArray) -> Unit)? = null
        val sent = ArrayList<ClusterThemeProtocol.Response>()
        var closed = false
        var alive = true
        var identityCount = 0
        var identityFailAt = Int.MAX_VALUE
        var carrierValid = true
        var checks = 0
        var baseline = selection()
        var queryCount = 0
        var afterLoad = selection(0, ClusterThemePolicy.HOST_DIRECTORY + name)
        var emitAck = true
        var ackKind = 2
        var ackPath = ClusterThemePolicy.HOST_DIRECTORY + name
        var customSend: ((ClusterThemeProtocol.Response) -> Unit)? = null
        override fun verifyIdentity() { check(++identityCount < identityFailAt) { "Java APK changed" } }
        override fun alive() = alive
        override fun register(receiver: (Int, ByteArray) -> Unit): Int { this.receiver = receiver; return 0 }
        override fun send(bytes: ByteArray): Int {
            val request = ClusterThemeProtocol.decode(bytes); sent.add(request)
            if (customSend != null) customSend!!.invoke(request)
            else if (request.cmd == 1) receiver!!(1000, if (++queryCount == 1) baseline else if (sent.any { it.cmd == 5 && it.ints[0] == 0 }) afterLoad else selection())
            else if (emitAck) receiver!!(1000, ack(if (request.ints[0] == 1) "default" else ackPath, ackKind))
            return 0
        }
        override fun verifyCarrier() { checks++; check(carrierValid) { "Carrier changed" } }
        override fun close() { closed = true }
        fun run(operation: String = "load", deadline: Long = 7000) = ClusterThemeIviExchange.execute(operation, name, this,
            deadline, { time }, { time += 25 })
    }
    @Test fun `exact default then one load ack and fresh selected query succeed without pixel claim`() {
        val f = Fixture(); val result = f.run()
        assertNull(result.error); assertTrue(result.attempted); assertEquals(1, f.checks)
        assertEquals(listOf(1, 5, 1), f.sent.map { it.cmd }); assertEquals(0, result.native)
        assertTrue(result.trace.any { "callbackCorrelation=UNAVAILABLE" in it })
        assertTrue(result.trace.any { "pixels=UNKNOWN" in it }); assertTrue(f.closed)
    }
    @Test fun `custom selection wrong version and missing baseline never send a mutation`() {
        for (baseline in listOf(selection(0, "other.rcc"), selection(version = "other"))) {
            val f = Fixture(); f.baseline = baseline; val result = f.run()
            assertNotNull(result.error); assertFalse(result.attempted); assertEquals(listOf(1), f.sent.map { it.cmd })
        }
        val f = Fixture(); f.customSend = {}; val result = f.run()
        assertNotNull(result.error); assertFalse(result.attempted)
    }
    @Test fun `changed identity carrier and dead binder refuse before mutation`() {
        val cases = listOf(Fixture().apply { identityFailAt = 2 }, Fixture().apply { carrierValid = false }, Fixture().apply { alive = false })
        for (f in cases) { val result = f.run(); assertNotNull(result.error); assertFalse(result.attempted); assertFalse(f.sent.any { it.cmd == 5 }); assertTrue(f.closed) }
    }
    @Test fun `native zero and ACK4 without owned postquery cannot establish load`() {
        for (f in listOf(Fixture().apply { emitAck = false }, Fixture().apply { ackKind = 4 })) {
            f.afterLoad = selection()
            val result = f.run(); assertNotNull(result.error); assertTrue(result.attempted)
            assertEquals(1, f.sent.count { it.cmd == 5 })
        }
    }
    @Test fun `hot reload with exact fresh own query but no ACK2 requires visual settlement`() {
        val f = Fixture(); f.emitAck = false
        val result = f.run(); assertNull(result.error); assertTrue(result.attempted)
        assertTrue(ClusterThemeProtocol.decode(result.postQueryResponses.last()).selected(name))
        assertFalse(result.responses.map { ClusterThemeProtocol.decode(it) }.any { ClusterThemePolicy.loadAckMatches(it, name) })
        assertTrue(result.trace.any { "load-not-settled" in it }); assertEquals(1, f.sent.count { it.cmd == 5 })
    }
    @Test fun `wrong owned filename ack is conflicting evidence and never retried`() {
        val f = Fixture(); f.ackPath = ClusterThemePolicy.HOST_DIRECTORY + name.replace("aaaa", "bbbb")
        val result = f.run(); assertNotNull(result.error); assertTrue(result.error!!.contains("Conflicting"))
        assertEquals(1, f.sent.count { it.cmd == 5 })
    }
    @Test fun `ACK2 followed by OEM ACK3 remains unknown even when the queried path is ours`() {
        val f = Fixture()
        f.customSend = { request ->
            if (request.cmd == 1) f.receiver!!(1000, if (++f.queryCount == 1) selection() else f.afterLoad)
            else { f.receiver!!(1000, ack()); f.receiver!!(1000, ack(kind = 3)) }
        }
        val result = f.run(); assertNotNull(result.error); assertTrue(result.error!!.contains("ACK3"))
        assertEquals(1, f.sent.count { it.cmd == 5 }); assertTrue(result.attempted)
    }
    @Test fun `post-query must identify exact host path not Android alias`() {
        val f = Fixture(); f.afterLoad = selection(0, ClusterThemePolicy.ANDROID_DIRECTORY + name)
        val result = f.run(); assertNotNull(result.error); assertTrue(result.error!!.contains("Post-query"))
    }
    @Test fun `broadcast conflicts malformed and overflow are unknown before mutation`() {
        for (fault in 0..3) {
            val f = Fixture(); f.customSend = {
                when (fault) {
                    0 -> { f.receiver!!(1000, selection()); f.receiver!!(1000, selection(0, "other.rcc")) }
                    1 -> f.receiver!!(1000, ByteArray(9))
                    2 -> repeat(33) { f.receiver!!(1000, selection()) }
                    3 -> f.receiver!!(2000, selection())
                }
            }
            val result = f.run(); assertNotNull(result.error); assertFalse(result.attempted); assertFalse(f.sent.any { it.cmd == 5 })
        }
    }
    @Test fun `expired window never authorizes a late response or replay`() {
        val f = Fixture(); val result = f.run(deadline = 500)
        assertNotNull(result.error); assertFalse(result.attempted); assertTrue(f.closed)
        f.receiver!!(1000, selection()); assertEquals(1, result.responses.size)
    }
    @Test fun `restore uses mode one then fresh default query`() {
        val f = Fixture(); f.baseline = selection(0, ClusterThemePolicy.HOST_DIRECTORY + name)
        val result = f.run("restore"); assertNull(result.error); assertTrue(result.attempted)
        val change = f.sent.single { it.cmd == 5 }; assertEquals(1, change.ints[0]); assertEquals("", change.strings[0]); assertEquals(0, f.checks)
    }
    @Test fun `restore SAME without default selection does not count as recovery`() {
        val f = Fixture(); f.baseline = selection(0, ClusterThemePolicy.HOST_DIRECTORY + name)
        f.customSend = { request -> if (request.cmd == 1) f.receiver!!(1000, f.baseline) }
        val result = f.run("restore"); assertNotNull(result.error); assertTrue(result.error!!.contains("Post-query"))
        assertEquals(1, f.sent.count { it.cmd == 5 })
    }
    @Test fun `read query does not send a carrier or restore`() {
        val f = Fixture(); val result = f.run("query")
        assertNull(result.error); assertFalse(result.attempted); assertEquals(listOf(1), f.sent.map { it.cmd }); assertEquals(0, f.checks)
    }
    @Test fun `journal transport never reinterprets legacy or unknown formats`() {
        assertEquals(ClusterThemeTransport.STRICT_RENDERER, ClusterThemeTransport.recover(1, null))
        assertEquals(ClusterThemeTransport.STRICT_RENDERER, ClusterThemeTransport.recover(2L, null))
        assertEquals(ClusterThemeTransport.ANDROID_IVI, ClusterThemeTransport.recover(3, "android-ivi"))
        for ((schema, transport) in listOf(1 to "android-ivi", 2 to "android-ivi", 3 to null, 3 to "strict-renderer", "3" to "android-ivi", 3.5 to "android-ivi"))
            assertThrows(IllegalArgumentException::class.java) { ClusterThemeTransport.recover(schema, transport) }
    }
}
