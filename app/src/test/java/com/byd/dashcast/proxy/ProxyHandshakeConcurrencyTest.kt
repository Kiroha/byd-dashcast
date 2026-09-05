package com.byd.dashcast.proxy

import android.os.Binder
import android.os.DeadObjectException
import android.os.Parcel
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ProxyHandshakeConcurrencyTest {

    @After
    fun reset() {
        setStatic("sBinder", null)
        setStatic("sDaemonUid", -1)
        setStatic("sDaemonPid", -1)
        setStatic("sDaemonVer", null)
        setStatic("sDaemonInstance", null)
        setStatic("sDeath", null)
        setStatic("sDeathBinder", null)
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

    @Test
    fun `binder arriving after fast snapshot is handshaken before connect succeeds`() {
        val calls = AtomicInteger()
        val connected = AtomicBoolean(false)
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                calls.incrementAndGet()
                reply!!.writeNoException()
                reply.writeInt(2000)
                reply.writeInt(456)
                reply.writeString("24")
                return true
            }
        }
        setStatic("sBinder", null)
        setStatic("sDaemonUid", -1)
        val lock = ProxyClient::class.java.getDeclaredField("LOCK").run {
            isAccessible = true
            get(null)!!
        }
        val connecting = Thread {
            connected.set(ProxyClient.connect(RuntimeEnvironment.getApplication()))
        }

        synchronized(lock) {
            connecting.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (connecting.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, connecting.state)
            setStatic("sBinder", binder)
        }

        connecting.join(1_000)
        assertFalse("connect thread did not finish", connecting.isAlive)
        assertTrue(connected.get())
        assertEquals(1, calls.get())
        assertEquals(2000, getStatic("sDaemonUid"))
    }

    @Test
    fun `malformed WHOAMI never makes connect succeed or escape`() {
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                reply!!.writeNoException()
                reply.writeInt(-1)
                reply.writeInt(0)
                reply.writeString(null)
                return true
            }
        }
        setStatic("sBinder", binder)
        setStatic("sDaemonUid", -1)

        assertFalse(ProxyClient.connect(RuntimeEnvironment.getApplication()))
        assertFalse(ProxyClient.isConnected())
        assertEquals(-1, getStatic("sDaemonUid"))
    }

    @Test
    fun `protocol 25 publishes exact daemon instance identity`() {
        val token = "0123456789abcdef0123456789abcdef"
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                reply!!.writeNoException()
                reply.writeInt(2000)
                reply.writeInt(789)
                reply.writeString("25")
                reply.writeString(token)
                return true
            }
        }
        setStatic("sBinder", binder)
        setStatic("sDaemonUid", -1)

        assertTrue(ProxyClient.connect(RuntimeEnvironment.getApplication()))
        assertEquals(token, getStatic("sDaemonInstance"))
        assertTrue(ProxyClient.captureDaemonIdentity() != null)
    }

    @Test
    fun `receiver invalidates old identity before publishing replacement binder`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it,
                "app/src/main/java/com/byd/dashcast/proxy/ProxyClient.kt").isFile }
        assertTrue("could not locate the repo root", root != null)
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/proxy/ProxyClient.kt").readText()
        val receiverPublish = source.substringAfter("// Unhook the previous death recipient")
            .substringBefore("AppLogger.i(TAG, \"live binder received from daemon\")")

        assertTrue(receiverPublish.indexOf("sDaemonUid = -1") <
            receiverPublish.indexOf("sBinder = incoming"))
    }

    @Test
    fun `death recipient clears only the binder it captured`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/proxy/ProxyClient.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/proxy/ProxyClient.kt").readText()
        val death = source.substringAfter("private fun linkDeathLocked")
            .substringBefore("private fun unlinkDeathLocked")
        val receiver = source.substringAfter("// Unhook the previous death recipient")
            .substringBefore("AppLogger.i(TAG, \"live binder received from daemon\")")

        assertTrue(death.contains("if (sBinder !== watchedBinder) return"))
        assertTrue(death.contains("sBinder = null"))
        assertTrue(receiver.contains("unlinkDeathLocked(current)"))
        assertTrue(receiver.contains("linkDeathLocked(incoming)"))
    }

    @Test
    fun `connection clear cannot invalidate a replacement binder`() {
        val stale = Binder()
        val current = Binder()
        setStatic("sBinder", current)
        setStatic("sDaemonUid", 2000)
        setStatic("sDaemonPid", 456)
        setStatic("sDaemonVer", "25")
        setStatic("sDaemonInstance", "0123456789abcdef0123456789abcdef")

        assertFalse(ProxyClient.clearConnectionIfCurrent(stale))
        assertTrue(ProxyClient.isConnected())
        assertEquals(456, getStatic("sDaemonPid"))

        assertTrue(ProxyClient.clearConnectionIfCurrent(current))
        assertFalse(ProxyClient.isConnected())
        assertEquals(-1, getStatic("sDaemonPid"))
        assertEquals(null, getStatic("sDaemonInstance"))
    }

    @Test
    fun `silent death invalidation cannot clear a replacement binder`() {
        val stale = Binder()
        val current = Binder()
        setStatic("sBinder", current)
        setStatic("sDaemonUid", 2000)
        setStatic("sDaemonPid", 456)

        assertFalse(ProxyClient.invalidateBinderIfCurrent(stale, "test"))
        assertTrue(ProxyClient.isConnected())
        assertEquals(456, getStatic("sDaemonPid"))

        assertTrue(ProxyClient.invalidateBinderIfCurrent(current, "test"))
        assertFalse(ProxyClient.isConnected())
    }

    @Test
    fun `virtual display transact and cleanup use the same captured binder`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/proxy/ProxyClient.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/proxy/ProxyClient.kt").readText()
        val create = source.substringAfter("public static int createVirtualDisplay")
            .substringBefore("public static void releaseVirtualDisplay")

        assertTrue(create.contains("ProxyDisplayVerbs.createVirtualDisplay(\n                    b,"))
        assertTrue(create.contains("clearConnectionIfCurrent(b)"))
    }

    @Test
    fun `failed connection paths clear the complete captured generation`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/proxy/ProxyClient.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/proxy/ProxyClient.kt").readText()
        val failedWait = source.substringAfter("// Late-arrival recovery:")
            .substringBefore("result = isConnected()")
        val handshakeFailure = source.substringAfter("handshake failed (")
            .substringBefore("return false")

        assertTrue(failedWait.contains("val failedBinder = sBinder"))
        assertTrue(failedWait.contains("clearConnectionIfCurrent(failedBinder)"))
        assertTrue(handshakeFailure.contains("clearConnectionIfCurrent(expectedBinder)"))
    }

    @Test
    fun `manual transact failures invalidate only their captured binder`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/proxy/ProxyClient.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/proxy/ProxyClient.kt").readText()

        val probes = source.substringAfter("public static String runPhase4Probes")
            .substringBefore("public static int createVirtualDisplay")
        val batch = source.substringAfter("public static int canBatch")
            .substringBefore("public static int canInstrumentGet")
        assertTrue(probes.contains("invalidateBinderIfCurrent(b, \"Phase4Probes\")"))
        assertTrue(batch.contains("ProxyCanVerbs.canBatch(b, operations)"))
        assertTrue(batch.contains("invalidateBinderIfCurrent(b, \"canBatch\")"))
    }

    @Test
    fun `alive-looking dead binder is invalidated by generic retry path`() {
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                throw DeadObjectException()
            }
        }
        setStatic("sBinder", binder)
        setStatic("sDaemonUid", 2000)

        try {
            ProxyClient.runShell("echo test")
        } catch (_: ProxyClient.ProxyException) {}

        assertFalse(ProxyClient.isConnected())
    }

    @Test
    fun `failed pinned attempt cannot transact on or clear replacement binder`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                throw DeadObjectException()
            }
        }
        val replacementCalls = AtomicInteger()
        val replacement = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                replacementCalls.incrementAndGet()
                return false
            }
        }
        setStatic("sBinder", first)
        setStatic("sDaemonUid", 2000)
        val done = CountDownLatch(1)
        Thread {
            ProxyClient.setNonBlockingReconnect(true)
            try {
                ProxyClient.runShell("echo test")
            } catch (_: ProxyClient.ProxyException) {
            } finally {
                ProxyClient.setNonBlockingReconnect(false)
                done.countDown()
            }
        }.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        setStatic("sBinder", replacement)
        release.countDown()

        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertTrue(ProxyClient.isConnected())
        assertEquals(0, replacementCalls.get())
    }

    private fun setStatic(name: String, value: Any?) {
        ProxyClient::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(null, value)
        }
    }

    private fun getStatic(name: String): Any? =
        ProxyClient::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(null)
        }
}
