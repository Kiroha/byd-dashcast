package com.byd.dashcast.proxy

import android.app.Application
import android.os.Binder
import android.os.Parcel
import com.byd.dashcast.proxy.daemon.AdasTargetReader
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class AdasTargetProtocolTest {
    private val binderField = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
    private val version = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
    private var previous: Any? = null
    private var previousVersion: Any? = null
    @Before fun setup() { previous = binderField.get(null); previousVersion = version.get(null); version.set(null, "28") }
    @After fun restore() { binderField.set(null, previous); version.set(null, previousVersion) }

    @Test fun `exact firmware candidates have independent closed wire verb and preserve raw sentinel`() {
        val expected = mutableSetOf(0x3160000e)
        for (base in listOf(0x29500000, 0x37400000)) for (slot in 1..4) {
            val offsets = if (base == 0x29500000) listOf(0x0, 0x1c, 0x10, 0x30) else listOf(0x0, 0x34, 0x10, 0x18)
            offsets.forEach { expected += base + slot * 0x1000 + it }
        }
        assertEquals(expected, AdasTargetProperties.allowed.toSet())
        assertEquals(33, AdasTargetProperties.allowed.size)
        assertEquals(1038, AdasTargetProperties.DEVICE)
        assertNotEquals(ProxyDaemonContract.TXN_ADAS_BODYWORK_READ, ProxyDaemonContract.TXN_ADAS_TARGET_READ)
        val seen = mutableListOf<Int>()
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(ProxyDaemonContract.TXN_ADAS_TARGET_READ, code)
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                seen += data.readInt(); assertEquals(0, data.dataAvail())
                reply!!.writeNoException(); reply.writeInt(-10011); return true
            }
        }
        binderField.set(null, binder)
        assertEquals(-10011, ProxyClient.readAdasTarget(binder, 0x29501000))
        assertEquals(listOf(0x29501000), seen)
    }

    @Test fun `arbitrary IDs and old protocol fail before IPC or OEM manager construction`() {
        var calls = 0
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean { calls++; return false }
        }
        binderField.set(null, binder)
        for (key in listOf(0, 1000, -1, 0x29505000, 0x12d0002a)) {
            assertThrows(IllegalArgumentException::class.java) { ProxyClient.readAdasTarget(binder, key) }
            assertThrows(IllegalArgumentException::class.java) { AdasTargetReader.read(RuntimeEnvironment.getApplication(), key) }
        }
        version.set(null, "27")
        val error = assertThrows(ProxyClient.ProxyException::class.java) { ProxyClient.readAdasTarget(binder, AdasTargetProperties.LKS_MODE) }
        assertTrue(error.message!!.contains("protocol 28")); assertEquals(0, calls)
    }

    @Test fun `replacement failed transaction and malformed response are never replayed or decoded as zero`() {
        for (mode in listOf("replace", "unsupported", "missing", "denied")) {
            var calls = 0
            val binder = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    calls++
                    if (mode == "unsupported") return false
                    if (mode == "replace") binderField.set(null, Binder())
                    if (mode == "denied") reply!!.writeException(SecurityException("OEM denied"))
                    else { reply!!.writeNoException(); if (mode != "missing") reply.writeInt(1) }
                    return true
                }
            }
            binderField.set(null, binder)
            assertThrows(Exception::class.java) { ProxyClient.readAdasTarget(binder, AdasTargetProperties.LKS_MODE) }
            assertEquals(mode, 1, calls)
        }
    }
}
