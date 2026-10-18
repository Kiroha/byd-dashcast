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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterContextDeviceTest {
    private val expected = StrictFileRead.Expected(1, 100, 0x81a4, 3, 1, 1)
    private fun fixture(malformed: Boolean = false): Pair<OemBootstrapDevice, MutableList<Int>> {
        val calls = mutableListOf<Int>()
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                calls += code
                data.enforceInterface(Contract.DESCRIPTOR)
                val out = reply!!
                out.writeNoException()
                when (code) {
                    Contract.TXN_WHOAMI -> {
                        out.writeInt(2000); out.writeInt(99); out.writeString("26")
                        out.writeString("0123456789abcdef0123456789abcdef")
                    }
                    Contract.TXN_FILE_READ_CAPABILITIES -> { out.writeInt(1); out.writeInt(Contract.CAP_STRICT_FILE_READ) }
                    Contract.TXN_EXEC -> {
                        assertEquals(OemBootstrapPolicy.command(ClusterContextPolicy.script("inventory_before").first), data.readString())
                        assertEquals(0, data.dataAvail())
                        out.writeInt(0)
                        out.writeString(if (malformed) "malformed" else buildString {
                            append("BEGIN|${ClusterContextPolicy.PROFILE}|inventory_before\n")
                            ClusterContextPolicy.inventoryLimits.forEach { (id, cap) ->
                                val value = if (id == "ps") "PID NAME\n11 qtandroidnative\n" else "value\n"
                                val bytes = (value + "\n.DC_BOOT_RC.0\n").toByteArray()
                                append("BLOB|$id|$cap|${Base64.getEncoder().encodeToString(bytes)}\n")
                            }
                            append("END|${ClusterContextPolicy.PROFILE}|inventory_before\n")
                        })
                    }
                    Contract.TXN_READ_FILE_CHUNK_STRICT -> {
                        val request = StrictFileRead.request(data)
                        assertEquals(ClusterContextPolicy.PROVIDER, request.path)
                        assertEquals(expected, request.expected)
                        assertEquals(0L, request.offset)
                        assertEquals(3, request.length)
                        out.writeByteArray(byteArrayOf(0, -1, 7))
                    }
                    else -> fail("Unexpected physical operation $code")
                }
                return true
            }
        }
        return OemBootstrapDevice(binder, DiLinkContextTransport()::execute, OemBootstrapDevice.Scope.CLUSTER) to calls
    }

    @Test fun `closed cluster profile reuses identity capability EXEC and strict read wire without bootstrap`() {
        val (device, calls) = fixture()
        val identity = device.negotiate {}
        assertEquals("0123456789abcdef0123456789abcdef", identity["daemon_instance_nonce"])
        assertEquals(ClusterContextPolicy.inventoryLimits.size, device.metadata("inventory_before").size)
        val provider = OemBootstrapPolicy.Source(0, ClusterContextPolicy.PROVIDER, "ok", expected, emptyList())
        assertArrayEquals(byteArrayOf(0, -1, 7), device.chunk(provider, 0, 3))
        assertEquals(listOf(Contract.TXN_WHOAMI, Contract.TXN_FILE_READ_CAPABILITIES,
            Contract.TXN_EXEC, Contract.TXN_READ_FILE_CHUNK_STRICT), calls)
        val before = calls.size
        for (path in ClusterContextPolicy.paths.drop(1) + listOf("/tmp/libwindowsurface_android.so", "/init")) {
            try { device.chunk(provider.copy(path = path), 0, 3); fail(path) } catch (_: IllegalArgumentException) {}
        }
        try { device.metadata("pid_99;id", 99); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(before, calls.size)
    }

    @Test fun `malformed cluster metadata terminates without another source call or replay`() {
        val (device, calls) = fixture(malformed = true)
        device.negotiate {}
        try { device.metadata("inventory_before"); fail() } catch (_: IOException) {}
        assertTrue(device.terminated)
        val before = calls.size
        try { device.metadata("inventory_before"); fail() } catch (_: IOException) {}
        assertEquals(before, calls.size)
    }
}
