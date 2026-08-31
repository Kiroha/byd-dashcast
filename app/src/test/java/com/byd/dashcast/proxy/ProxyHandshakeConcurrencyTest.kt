package com.byd.dashcast.proxy

import android.os.Binder
import android.os.Parcel
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ProxyHandshakeConcurrencyTest {

    @After
    fun reset() {
        setStatic("sBinder", null)
        setStatic("sDaemonUid", -1)
        setStatic("sDaemonPid", -1)
        setStatic("sDaemonVer", null)
    }

    @Test
    fun `wedged handshake does not hold the proxy state lock`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invalidated = CountDownLatch(1)
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                reply!!.writeNoException()
                reply.writeInt(2000)
                reply.writeInt(123)
                reply.writeString("24")
                return true
            }
        }
        setStatic("sBinder", binder)
        setStatic("sDaemonUid", -1)

        val connecting = Thread {
            ProxyClient.connect(RuntimeEnvironment.getApplication())
        }
        connecting.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        val invalidating = Thread {
            ProxyClient.invalidateBinder("handshake-test")
            invalidated.countDown()
        }
        invalidating.start()
        assertTrue("invalidation blocked behind WHOAMI", invalidated.await(500, TimeUnit.MILLISECONDS))
        assertFalse(ProxyClient.isConnected())

        release.countDown()
        connecting.join(1_000)
        invalidating.join(1_000)
    }

    private fun setStatic(name: String, value: Any?) {
        ProxyClient::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(null, value)
        }
    }
}
