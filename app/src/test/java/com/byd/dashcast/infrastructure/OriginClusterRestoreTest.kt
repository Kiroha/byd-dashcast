package com.byd.dashcast.infrastructure

import android.app.Application
import android.os.Binder
import android.os.Parcel
import com.byd.dashcast.cluster.display.ProjectionCommandBus
import com.byd.dashcast.proxy.DaemonConfig
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real helper and Binder transport: the old callback completed three seconds before shape. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class OriginClusterRestoreTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val commands = Collections.synchronizedList(mutableListOf<Int>())
    private val terminal = CountDownLatch(1)
    private val finished = CountDownLatch(1)
    private val successes = AtomicInteger()
    private val failures = AtomicInteger()
    private val binderField = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
    private var previousBinder: Any? = null
    private var afterCommand: (Int) -> Unit = {}
    private var callbackThrows = false
    @Volatile private var interruptedAtCallback = false
    private val callback = object : AdbLocalClient.Callback {
        override fun onSuccess(out: String?) {
            successes.incrementAndGet()
            terminal.countDown()
            finished.countDown()
            if (callbackThrows) throw IllegalStateException("callback failed")
        }
        override fun onError(err: String?) {
            interruptedAtCallback = Thread.currentThread().isInterrupted
            failures.incrementAndGet()
            terminal.countDown()
            finished.countDown()
        }
    }

    @Before fun setup() {
        previousBinder = binderField.get(null)
        DaemonConfig.setLegacyPathEnabled(app, false)
        ProjectionCommandBus.beginSession()
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                assertEquals(ProxyDaemonContract.TXN_AUTOCONTAINER_SEND_INFO, code)
                assertEquals(1000, data.readInt())
                val command = data.readInt()
                commands += command
                afterCommand(command)
                reply!!.writeNoException()
                return true
            }
        })
    }
    @After fun cleanup() {
        assertTrue("restore worker must finish before changing global Binder", finished.await(8, TimeUnit.SECONDS))
        binderField.set(null, previousBinder)
        ProjectionCommandBus.currentSession()?.let(ProjectionCommandBus::endSession)
    }
    private fun restore(valid: () -> Boolean = { true }) =
        AdbLocalClient.restoreOriginCluster(app, 31, null, callback, valid)
    private fun awaitTerminal() {
        assertTrue("restore callback", terminal.await(8, TimeUnit.SECONDS))
        assertEquals(1, successes.get() + failures.get())
    }

    @Test fun `terminal callback waits for blocked final shape and retired owner remains valid`() {
        val shape = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = ProjectionCommandBus.currentSession()!!
        afterCommand = { command ->
            if (command == 0) ProjectionCommandBus.endSession(session)
            if (command == 31) {
                shape.countDown()
                check(release.await(8, TimeUnit.SECONDS))
            }
        }
        try {
            restore()
            assertTrue("final shape reached", shape.await(8, TimeUnit.SECONDS))
            assertEquals("neither UI nor ADAS cleanup may complete ahead of shape", 1L, terminal.count)
        } finally { release.countDown() }
        awaitTerminal()
        assertEquals(listOf(18, 0, 31), commands)
        assertEquals(1, successes.get())
    }
    @Test fun `final shape exception completes once without replaying native close`() {
        afterCommand = { if (it == 31) throw IllegalStateException("shape transport failed") }
        restore(); awaitTerminal()
        assertEquals(listOf(18, 0, 31), commands)
        assertEquals(1, failures.get())
    }
    @Test fun `interruption completes teardown once and preserves interrupt status`() {
        afterCommand = { if (it == 18) Thread.currentThread().interrupt() }
        restore(); awaitTerminal()
        assertEquals(listOf(18), commands)
        assertEquals(1, failures.get())
        assertTrue(interruptedAtCallback)
    }
    @Test fun `new projection during first settle prevents old refresh and shape`() {
        afterCommand = { if (it == 18) ProjectionCommandBus.beginSession() }
        restore(); awaitTerminal()
        assertEquals(listOf(18), commands)
        assertEquals(1, failures.get())
    }
    @Test fun `new projection during final settle prevents old shape`() {
        afterCommand = { if (it == 0) ProjectionCommandBus.beginSession() }
        restore(); awaitTerminal()
        assertEquals(listOf(18, 0), commands)
        assertEquals(1, failures.get())
    }
    @Test fun `cancelled upstream owner cannot send even the first close`() {
        restore { false }; awaitTerminal()
        assertTrue(commands.isEmpty())
        assertEquals(1, failures.get())
    }
    @Test fun `callback exception cannot cause legacy replay or second callback`() {
        callbackThrows = true
        restore(); awaitTerminal()
        assertEquals(listOf(18, 0, 31), commands)
        assertEquals(1, successes.get())
        assertEquals(0, failures.get())
    }
}
