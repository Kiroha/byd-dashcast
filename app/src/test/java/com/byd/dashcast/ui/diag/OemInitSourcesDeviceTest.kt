package com.byd.dashcast.ui.diag

import android.os.Binder
import android.os.Parcel
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract as Contract
import com.byd.dashcast.proxy.daemon.StrictFileRead
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OemInitSourcesDeviceTest {
    private val expected = StrictFileRead.Expected(1, 2, 0x81a4, 3, 4, 5)
    private val entry = OemInitSourcesPolicy.Entry(0, 1, Base64.getEncoder().encodeToString("/init.rc".toByteArray()), "/init.rc", "ok", expected)
    private fun device(mode: String, calls: MutableList<Int>, chunk: ((Parcel, Parcel) -> Unit)? = null): OemInitSourcesDevice {
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(Contract.DESCRIPTOR); calls.add(code)
                val out = reply!!
                if (code == Contract.TXN_READ_FILE_CHUNK_STRICT) {
                    val request = StrictFileRead.request(data); assertEquals(expected, request.expected)
                    if (chunk != null) { chunk(data, out); return true }
                    out.writeNoException()
                    when (mode) {
                        "missing" -> Unit
                        "oversized" -> out.writeInt(Int.MAX_VALUE)
                        "short" -> out.writeByteArray(byteArrayOf(1, 2))
                        "unknown-exception" -> { out.setDataPosition(0); out.writeInt(-999) }
                        "truncated-exception" -> { out.setDataPosition(0); out.writeInt(-5); out.writeString("missing stack field") }
                        "source-denied" -> { out.setDataPosition(0); out.writeInt(-5); out.writeString("java.io.IOException: source changed"); out.writeInt(0) }
                        else -> { out.writeByteArray(byteArrayOf(1, 2, 3)); if (mode == "trailing") out.writeInt(1) }
                    }
                } else {
                    out.writeNoException()
                    when (code) {
                        Contract.TXN_WHOAMI -> {
                            out.writeInt(if (mode == "root") 0 else 2000); out.writeInt(99)
                            out.writeString(if (mode == "old") "25" else "26"); out.writeString("0123456789abcdef0123456789abcdef")
                            if (mode == "identity-trailing") out.writeInt(1)
                        }
                        Contract.TXN_FILE_READ_CAPABILITIES -> {
                            out.writeInt(if (mode == "schema") 2 else 1)
                            if (mode != "cap-truncated") out.writeInt(if (mode == "missing-cap") 0 else 1)
                            if (mode == "cap-trailing") out.writeInt(1)
                        }
                        else -> fail("Unexpected transaction $code")
                    }
                }
                return true
            }
        }
        return OemInitSourcesDevice(binder, DiLinkContextTransport()::execute)
    }
    @Test fun `identity and strict capability refusal never submit source or legacy operations`() {
        for (mode in listOf("root", "old", "identity-trailing", "schema", "missing-cap", "cap-truncated", "cap-trailing")) {
            val calls = mutableListOf<Int>(); val device = device(mode, calls)
            try { device.negotiate {}; fail(mode) } catch (_: IOException) { }
            assertTrue(device.terminated)
            assertTrue(calls.all { it in listOf(Contract.TXN_WHOAMI, Contract.TXN_FILE_READ_CAPABILITIES) })
            val before = calls.size
            try { device.negotiate {}; fail(mode) } catch (_: IOException) { }
            assertEquals(before, calls.size)
        }
        try { OemInitSourcesDevice(null).negotiate {}; fail() } catch (_: IOException) { }
    }
    @Test fun `each strict reply is bounded before allocation and malformed frames terminate without replay`() {
        for (mode in listOf("valid", "missing", "oversized", "short", "trailing", "unknown-exception", "truncated-exception")) {
            val calls = mutableListOf<Int>(); val device = device(mode, calls); device.negotiate {}
            try { assertArrayEquals(byteArrayOf(1, 2, 3), device.chunk(entry, 0, 3)); assertEquals("valid", mode) }
            catch (_: IOException) { assertNotEquals("valid", mode); assertTrue(device.terminated) }
            assertEquals(listOf(Contract.TXN_WHOAMI, Contract.TXN_FILE_READ_CAPABILITIES, Contract.TXN_READ_FILE_CHUNK_STRICT), calls)
            if (mode != "valid") {
                try { device.chunk(entry, 0, 3); fail() } catch (_: IOException) { }
                assertEquals(3, calls.size)
            }
        }
    }
    @Test fun `complete bounded source exception remains a per file failure`() {
        val calls = mutableListOf<Int>(); val device = device("source-denied", calls); device.negotiate {}
        repeat(2) { try { device.chunk(entry, 0, 3); fail() } catch (e: IOException) { assertFalse(e.message, device.terminated) } }
        assertFalse(device.terminated); assertEquals(4, calls.size)
    }
    @Test fun `cancellation between identity and capability stops before another physical operation`() {
        val calls = mutableListOf<Int>(); val device = device("valid", calls)
        var checks = 0
        try { device.negotiate { if (++checks == 2) throw java.util.concurrent.CancellationException() }; fail() }
        catch (_: java.util.concurrent.CancellationException) { }
        assertEquals(listOf(Contract.TXN_WHOAMI), calls)
    }
    @Test fun `timed out typed call stays quarantined and late completion is never consumed or replayed`() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val operations = AtomicInteger()
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                operations.incrementAndGet(); started.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS))
                reply!!.writeNoException(); reply.writeInt(2000); reply.writeInt(1); reply.writeString("26"); reply.writeString("0123456789abcdef0123456789abcdef")
                return true
            }
        }
        val transport = DiLinkContextTransport(timeoutMs = 20)
        val device = OemInitSourcesDevice(binder, transport::execute)
        try {
            try { device.negotiate {}; fail() } catch (_: IOException) { }
            assertTrue(started.await(1, TimeUnit.SECONDS)); assertTrue(transport.isBusy()); assertTrue(device.terminated)
            val other = OemInitSourcesDevice(binder, transport::execute)
            try { other.negotiate {}; fail() } catch (_: IOException) { }
            assertEquals(1, operations.get())
        } finally { release.countDown() }
        val idle = CountDownLatch(1); transport.whenIdle { idle.countDown() }
        assertTrue(idle.await(1, TimeUnit.SECONDS)); assertEquals(1, operations.get())
        try { device.negotiate {}; fail() } catch (_: IOException) { }
        assertEquals(1, operations.get())
    }
}
