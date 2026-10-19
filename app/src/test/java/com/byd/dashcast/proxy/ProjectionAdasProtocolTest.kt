package com.byd.dashcast.proxy

import android.app.Application
import android.os.Binder
import android.os.Parcel
import android.os.RemoteException
import com.byd.dashcast.proxy.daemon.AdasBodyworkReader
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
class ProjectionAdasProtocolTest {
    private val field = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
    private val version = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
    private val previous = field.get(null)
    private val previousVersion = version.get(null)
    @Before fun setup() { version.set(null, "27") }
    @After fun cleanup() { field.set(null, previous); version.set(null, previousVersion) }
    @Test fun `typed power and raw monitor remain distinct wire operations and values`() {
        val requests = mutableListOf<Int>()
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(ProxyDaemonContract.TXN_ADAS_BODYWORK_READ, code)
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                val property = data.readInt(); requests += property
                reply!!.writeNoException(); reply.writeInt(if (property == 0) 2 else 3)
                return true
            }
        }
        field.set(null, binder)
        assertEquals(2, ProxyClient.readAdasBodywork(binder, 0))
        assertEquals(3, ProxyClient.readAdasBodywork(binder, 0x12d0002a))
        assertEquals(listOf(0, 0x12d0002a), requests)
    }
    @Test fun `old daemon and arbitrary property are refused without IPC`() {
        var calls = 0
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean { calls++; return false }
        }
        field.set(null, binder); version.set(null, "26")
        assertThrows(ProxyClient.ProxyException::class.java) { ProxyClient.readAdasBodywork(binder, 0) }
        assertThrows(IllegalArgumentException::class.java) { ProxyClient.readAdasBodywork(binder, 123) }
        assertThrows(IllegalArgumentException::class.java) { AdasBodyworkReader.read(RuntimeEnvironment.getApplication(), 123) }
        assertEquals(0, calls)
    }
    @Test fun `replaced daemon read is rejected and never replayed`() {
        var calls = 0
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                calls++; field.set(null, Binder())
                reply!!.writeNoException(); reply.writeInt(2); return true
            }
        }
        field.set(null, binder)
        assertThrows(ProxyClient.ProxyException::class.java) { ProxyClient.readAdasBodywork(binder, 0) }
        assertEquals(1, calls)
    }
    @Test fun `uncertain shell failure is a single attempt with no reconnect replay`() {
        var calls = 0
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                calls++
                throw RemoteException("response lost after possible effect")
            }
        }
        field.set(null, binder)
        assertThrows(RemoteException::class.java) { ProxyClient.runShellOnce(binder, "test-fixed-command") }
        assertEquals(1, calls)
        assertSame(binder, field.get(null))
    }
}
