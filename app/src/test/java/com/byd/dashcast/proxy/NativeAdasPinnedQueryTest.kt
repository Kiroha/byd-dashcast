package com.byd.dashcast.proxy

import android.app.Application
import android.os.Binder
import android.os.Parcel
import com.byd.dashcast.cluster.NativeAdasWindowControl
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class NativeAdasPinnedQueryTest {
    private val field = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
    private val previous = field.get(null)
    @After fun cleanup() { field.set(null, previous) }
    @Test fun `stable query transacts once and a mid query replacement invalidates its value`() {
        for (replace in listOf(false, true)) {
            var calls = 0
            val replacement = Binder()
            val binder = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    calls++
                    assertEquals(ProxyDaemonContract.TXN_CAN_INSTRUMENT_GET, code)
                    data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                    assertEquals(NativeAdasWindowControl.PROPERTY, data.readInt())
                    if (replace) field.set(null, replacement)
                    reply!!.writeNoException(); reply.writeInt(1)
                    return true
                }
            }
            field.set(null, binder)
            try {
                assertEquals(1, ProxyClient.canInstrumentGetOnCurrentBinder(binder, NativeAdasWindowControl.PROPERTY))
                assertFalse(replace)
            } catch (_: ProxyClient.ProxyException) { assertTrue(replace) }
            assertEquals(1, calls)
            if (replace) assertSame(replacement, field.get(null))
        }
    }
    @Test fun `already replaced query never dispatches or reconnects`() {
        val stale = Binder(); val current = Binder(); field.set(null, current)
        try {
            ProxyClient.canInstrumentGetOnCurrentBinder(stale, NativeAdasWindowControl.PROPERTY)
            fail()
        } catch (_: ProxyClient.ProxyException) { }
        assertSame(current, field.get(null))
    }
}
