package com.byd.dashcast.fission

import android.os.Binder
import android.os.Parcel
import com.byd.dashcast.proxy.FissionResourceOwner
import com.byd.dashcast.proxy.daemon.SurfaceDaemon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FissionClientOwnerWireTest {

    @Test
    fun `attach slot sends the process owner after the legacy fields`() {
        val daemon = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(SurfaceDaemon.TRANSACT_ATTACH_SLOT, code)
                data.enforceInterface(SurfaceDaemon.DESCRIPTOR)
                assertEquals("com.example.nav", data.readString())
                assertEquals(10, data.readInt())
                assertEquals(20, data.readInt())
                assertEquals(800, data.readInt())
                assertEquals(480, data.readInt())
                assertSame(FissionResourceOwner.token(), data.readStrongBinder())
                reply!!.writeNoException()
                reply.writeInt(0)
                return true
            }
        }

        assertEquals(-1, FissionClient.attachSlot(
            daemon, "com.example.nav", 10, 20, 800, 480
        ))
    }
}