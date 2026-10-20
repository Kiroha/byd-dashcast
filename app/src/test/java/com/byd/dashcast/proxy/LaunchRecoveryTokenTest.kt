package com.byd.dashcast.proxy

import android.app.Application
import android.os.Binder
import android.os.Parcel
import com.byd.dashcast.proxy.daemon.LaunchRecoveryToken
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class LaunchRecoveryTokenTest {
    @Test fun `cancel before daemon entry cannot be lost and old request cannot cancel new request`() {
        val epoch = AtomicLong(1)
        val first = LaunchRecoveryToken.create { epoch.get() == 1L }
        epoch.incrementAndGet()
        val second = LaunchRecoveryToken.create { epoch.get() == 2L }
        assertFalse(LaunchRecoveryToken.isCurrent(first))
        assertTrue(LaunchRecoveryToken.isCurrent(second))
        assertFalse(LaunchRecoveryToken.isCurrent(first))
        assertTrue(LaunchRecoveryToken.isCurrent(second))
    }
    @Test fun `missing unsupported malformed throwing and dead callback fail closed`() {
        assertFalse(LaunchRecoveryToken.isCurrent(null))
        assertFalse(LaunchRecoveryToken.isCurrent(Binder()))
        assertFalse(LaunchRecoveryToken.isCurrent(LaunchRecoveryToken.create { throw IllegalStateException() }))
        val dead = object : Binder() { override fun isBinderAlive() = false }
        assertFalse(LaunchRecoveryToken.isCurrent(dead))
        val malformed = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                reply!!.writeNoException(); return true
            }
        }
        assertFalse(LaunchRecoveryToken.isCurrent(malformed))
    }
    @Test fun `old wire omits token while protocol 29 carries optional token`() {
        val binderField = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        val versionField = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
        val previous = binderField.get(null); val previousVersion = versionField.get(null)
        try {
            for (version in listOf("28", "29")) for (withToken in listOf(false, true)) {
                versionField.set(null, version)
                val token = if (withToken) LaunchRecoveryToken.create { true } else null
                binderField.set(null, object : Binder() {
                    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                        assertEquals(ProxyDaemonContract.TXN_LAUNCH_AND_FORCE, code)
                        data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                        assertEquals("com.waze", data.readString()); assertEquals(0, data.readInt())
                        assertEquals(2, data.readInt()); assertEquals(1920, data.readInt()); assertEquals(720, data.readInt())
                        if (version == "28") assertEquals(0, data.dataAvail())
                        val received = LaunchRecoveryToken.readOptional(data)
                        assertEquals(version == "29" && withToken, LaunchRecoveryToken.isCurrent(received))
                        reply!!.writeNoException(); reply.writeString("legacy launch result"); return true
                    }
                })
                assertEquals("legacy launch result", ProxyFissionVerbs.launchAndForce("com.waze", null, 2, 1920, 720, token))
            }
        } finally { binderField.set(null, previous); versionField.set(null, previousVersion) }
    }
}
