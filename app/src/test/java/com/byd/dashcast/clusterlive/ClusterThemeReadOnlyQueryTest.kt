package com.byd.dashcast.clusterlive

import com.google.flatbuffers.FlatBufferBuilder
import org.junit.Assert.*
import org.junit.Test

class ClusterThemeReadOnlyQueryTest {
    private var now = 0L
    private class Fake : ClusterThemeReadOnlyQuery.Transport {
        var verified = 0
        var closed = false
        var alive = true
        var registrations = 0
        var sends = 0
        var identityFailure = false
        var registrationResult = 0
        var sendResult = 0
        var receiver: ((Int, ByteArray) -> Unit)? = null
        var onSend: (Fake) -> Unit = {}
        var onRegister: (Fake) -> Unit = {}
        override fun verifyIdentity() { verified++; check(!identityFailure) { "APK/UID identity mismatch" } }
        override fun alive() = alive
        override fun register(receiver: (Int, ByteArray) -> Unit): Int {
            registrations++; this.receiver = receiver; onRegister(this); return registrationResult
        }
        override fun sendQuery(bytes: ByteArray): Int {
            sends++
            val request = ClusterThemeProtocol.decode(bytes)
            assertEquals(1, request.cmd); assertEquals(4, request.sub)
            assertTrue(request.ints.all { it == 0 }); assertTrue(request.strings.all { it.isEmpty() })
            assertArrayEquals(ClusterThemeProtocol.query(), bytes)
            onSend(this); return sendResult
        }
        override fun close() { closed = true }
    }
    private fun response(version: String = ClusterThemeProtocol.UI_VERSION): ByteArray {
        val b = FlatBufferBuilder(256); val ui = b.createString(version); val path = b.createString("default")
        b.startTable(13); b.addInt(0, 1001, 0); b.addInt(1, 4, 0); b.addInt(2, 1, 0); b.addInt(5, 1, 0)
        b.addOffset(7, ui, 0); b.addOffset(9, path, 0); b.addOffset(11, path, 0)
        b.finish(b.endTable()); return b.sizedByteArray()
    }
    private fun run(fake: Fake, pause: () -> Unit = { now += 50 }) =
        ClusterThemeReadOnlyQuery.execute(fake, 500, { now }, pause)

    @Test fun `one query retains registration and native result separately without load or restore`() {
        val fake = Fake().apply { onSend = { it.receiver!!(1000, response()) } }
        val receipt = run(fake)
        assertEquals("RECEIVED", receipt.state); assertEquals(0, receipt.registration); assertEquals(0, receipt.native)
        assertTrue(receipt.response!!.isDefault()); assertEquals(1, fake.registrations); assertEquals(1, fake.sends)
        assertEquals(2, fake.verified); assertTrue(fake.closed)
    }
    @Test fun `failed APK or UID verification admits neither registration nor query`() {
        val fake = Fake().apply { identityFailure = true }
        val receipt = run(fake)
        assertEquals("UNAVAILABLE", receipt.state); assertEquals("java-identity", receipt.phase)
        assertEquals(0, fake.registrations); assertEquals(0, fake.sends); assertTrue(fake.closed)
    }
    @Test fun `registration refusal cannot send query`() {
        val fake = Fake().apply { registrationResult = -1 }
        val receipt = run(fake)
        assertEquals(-1, receipt.registration); assertNull(receipt.native); assertEquals(0, fake.sends)
    }
    @Test fun `nonzero native result does not promote even a valid callback and is never retried`() {
        val fake = Fake().apply { sendResult = -1; onSend = { it.receiver!!(1000, response()) } }
        val receipt = run(fake)
        assertEquals("UNAVAILABLE", receipt.state); assertEquals(-1, receipt.native); assertNull(receipt.response)
        assertEquals(1, fake.sends)
    }
    @Test fun `Binder death before send prevents query and death after send prevents received status`() {
        val before = Fake().apply { onRegister = { it.alive = false } }
        assertEquals("UNAVAILABLE", run(before).state); assertEquals(0, before.sends)
        val after = Fake().apply { onSend = { it.receiver!!(1000, response()); it.alive = false } }
        assertEquals("UNAVAILABLE", run(after).state); assertEquals(1, after.sends)
    }
    @Test fun `wrong UID and malformed messages cannot become selection evidence`() {
        val fake = Fake().apply { onSend = {
            it.receiver!!(2000, response()); it.receiver!!(1000, ByteArray(12)); it.receiver!!(1000, response())
        } }
        val receipt = run(fake)
        assertEquals("RECEIVED", receipt.state); assertEquals(1, receipt.rejected); assertEquals(1, receipt.malformed)
    }
    @Test fun `callbacks before send and after deadline or close cannot become query evidence`() {
        val fake = Fake().apply { onRegister = { it.receiver!!(1000, response()) } }
        val receipt = run(fake) { now = 500; fake.receiver!!(1000, response()) }
        assertEquals("UNAVAILABLE", receipt.state); assertNull(receipt.response); assertEquals(1, fake.sends)
        fake.receiver!!(1000, response())
        assertNull(receipt.response)
    }
    @Test fun `unknown UI version is observation only and never becomes matching default`() {
        val fake = Fake().apply { onSend = { it.receiver!!(1000, response("different-version")) } }
        val receipt = run(fake)
        assertEquals("RECEIVED", receipt.state); assertFalse(receipt.response!!.isDefault())
        assertTrue(receipt.detail.contains("UNKNOWN")); assertEquals(1, fake.sends)
    }
    @Test fun `identity change after response invalidates the observation`() {
        val fake = Fake().apply { onSend = { it.receiver!!(1000, response()); it.identityFailure = true } }
        val receipt = run(fake)
        assertEquals("UNAVAILABLE", receipt.state); assertEquals("java-identity-recheck", receipt.phase)
        assertNull(receipt.response); assertEquals(1, fake.sends)
    }
}
