package com.byd.dashcast.hud

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises Android Parcel marshalling and binding races without an OEM server or vehicle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SomeIpHudTransportTest {
    private val component = ComponentName(SomeIpHudTransport.PACKAGE, SomeIpHudTransport.SERVICE)

    @Test
    fun `bind targets only the explicit OEM service and repeated requests share one binding`() {
        val context = BindingContext()
        val transport = SomeIpHudTransport(context)
        assertEquals(SomeIpHudBindResult.REQUESTED, transport.bind())
        assertEquals(SomeIpHudBindResult.REQUESTED, transport.bind())
        assertEquals(1, context.bindings.size)
        assertEquals(component, context.intent!!.component)
        assertEquals("com.ts.car.someip.SomeIpServerService", context.intent!!.action)
        assertEquals(Context.BIND_AUTO_CREATE, context.flags)
        assertFalse(transport.isBinderAvailable)
        assertEquals(failure(SomeIpHudTransactionResult.Reason.NOT_CONNECTED), transport.startService(1))
        transport.close()
        transport.close()
        assertEquals(1, context.unbound.size)
    }

    @Test
    fun `start event and stop preserve 64 bit identifiers and exact Parcel fields`() {
        val binder = RecordingBinder()
        val (transport, _) = connected(binder)
        val service = 0x000B820282020000L
        val topic = 0x000482028202800BL
        val payload = byteArrayOf(0x0A, 0x02, 0x08, 0x7F)
        binder.returnCode = -37
        assertEquals(SomeIpHudTransactionResult.Reply(-37), transport.startService(service))
        assertEquals(SomeIpHudTransactionResult.Reply(-37), transport.fireEvent(topic, payload))
        assertEquals(SomeIpHudTransactionResult.Reply(-37), transport.stopService(service))
        assertEquals(listOf(4, 6, 5), binder.calls.map { it.code })
        assertEquals(listOf(service, topic, service), binder.calls.map { it.id })
        val event = binder.calls[1]
        assertEquals(1, event.fixedInt)
        assertEquals(0L, event.fixedLong)
        assertEquals(payload.size, event.explicitSize)
        assertArrayEquals(payload, event.payload)
        assertTrue(binder.calls.all { it.flags == 0 && it.remainingBytes == 0 })
        assertEquals(1, binder.descriptorReads)
        assertEquals(1, binder.deaths.size)
        transport.close()
        assertEquals(0, binder.deaths.size)
        assertEquals("close must not issue an unverified global service stop", 3, binder.calls.size)
    }

    @Test
    fun `empty event carries both zero lengths`() {
        val binder = RecordingBinder()
        val (transport, _) = connected(binder)
        assertEquals(SomeIpHudTransactionResult.Reply(0), transport.fireEvent(Long.MAX_VALUE, byteArrayOf()))
        assertEquals(0, binder.calls.single().explicitSize)
        assertArrayEquals(byteArrayOf(), binder.calls.single().payload)
        transport.close()
    }

    @Test
    fun `Android connection callback does not look up the descriptor or transmit`() {
        val binder = RecordingBinder()
        val (transport, _) = connected(binder)
        assertTrue(transport.isBinderAvailable)
        assertEquals(0, binder.descriptorReads)
        assertTrue(binder.deaths.isEmpty())
        assertTrue(binder.calls.isEmpty())
        transport.close()
    }

    @Test
    fun `wrong descriptor retires the binding before any OEM transaction`() {
        val binder = RecordingBinder("another.interface")
        val (transport, context) = connected(binder)
        assertEquals(failure(SomeIpHudTransactionResult.Reason.INVALID_INTERFACE), transport.startService(1))
        assertTrue(binder.calls.isEmpty())
        assertTrue(binder.deaths.isEmpty())
        assertFalse(transport.isBinderAvailable)
        assertEquals(1, context.unbound.size)
        transport.close()
        assertEquals(1, context.unbound.size)
    }

    @Test
    fun `a descriptor read failure releases the binding without claiming certain Binder death`() {
        for ((exception, reason) in listOf(
            RemoteException() to SomeIpHudTransactionResult.Reason.REMOTE_EXCEPTION,
            SecurityException() to SomeIpHudTransactionResult.Reason.PERMISSION_DENIED
        )) {
            val binder = RecordingBinder().apply { descriptorException = exception }
            val (transport, context) = connected(binder)
            assertEquals(failure(reason), transport.startService(1))
            assertFalse(transport.isBinderAvailable)
            assertTrue(binder.calls.isEmpty())
            assertTrue(binder.deaths.isEmpty())
            assertEquals(1, context.unbound.size)
        }
    }

    @Test
    fun `stop before onServiceConnected ignores the late callback and releases the binding`() {
        val context = BindingContext()
        val transport = SomeIpHudTransport(context)
        transport.bind()
        transport.close()
        val binder = RecordingBinder()
        context.bindings.single().onServiceConnected(component, binder)
        assertFalse(transport.isBinderAvailable)
        assertEquals(failure(SomeIpHudTransactionResult.Reason.NOT_CONNECTED), transport.fireEvent(1, byteArrayOf(1)))
        assertEquals(1, context.unbound.size)
        assertTrue(binder.calls.isEmpty())
    }

    @Test
    fun `close during bindService releases registration once bindService returns`() {
        val context = BindingContext()
        val transport = SomeIpHudTransport(context)
        context.duringBind = { transport.close() }
        assertEquals(SomeIpHudBindResult.CANCELLED, transport.bind())
        assertEquals(context.bindings, context.unbound)
        transport.close()
        assertEquals(1, context.unbound.size)
    }

    @Test
    fun `null binding received before bindService returns is still released once`() {
        val context = BindingContext()
        val transport = SomeIpHudTransport(context)
        context.duringBind = { context.bindings.single().onNullBinding(component) }
        assertEquals(SomeIpHudBindResult.CANCELLED, transport.bind())
        assertEquals(1, context.unbound.size)
        transport.close()
        assertEquals(1, context.unbound.size)
    }

    @Test
    fun `rejected and throwing binds release tracking resources and permit a new request`() {
        for (problem in listOf<RuntimeException?>(null, SecurityException(), IllegalStateException())) {
            val context = BindingContext().apply {
                accept = false
                bindException = problem
            }
            val transport = SomeIpHudTransport(context)
            val expected = when (problem) {
                null -> SomeIpHudBindResult.REJECTED
                is SecurityException -> SomeIpHudBindResult.PERMISSION_DENIED
                else -> SomeIpHudBindResult.FAILED
            }
            assertEquals(expected, transport.bind())
            assertEquals(1, context.unbound.size)
            context.accept = true
            context.bindException = null
            assertEquals(SomeIpHudBindResult.REQUESTED, transport.bind())
            assertEquals(2, context.bindings.size)
            transport.close()
            assertEquals(2, context.unbound.size)
        }
    }

    @Test
    fun `callbacks from an old binding cannot overwrite or stop its replacement`() {
        val context = BindingContext()
        val transport = SomeIpHudTransport(context)
        transport.bind()
        val old = context.bindings.single()
        transport.close()
        transport.bind()
        val currentBinder = RecordingBinder()
        context.bindings.last().onServiceConnected(component, currentBinder)
        old.onServiceConnected(component, RecordingBinder())
        old.onServiceDisconnected(component)
        old.onBindingDied(component)
        old.onNullBinding(component)
        assertEquals(SomeIpHudTransactionResult.Reply(0), transport.startService(42))
        assertEquals(42L, currentBinder.calls.single().id)
        assertEquals(1, context.unbound.size)
        transport.close()
    }

    @Test
    fun `disconnect suspends writes and Android reconnect revalidates without replay`() {
        val first = RecordingBinder()
        val (transport, context) = connected(first)
        transport.startService(7)
        val connection = context.bindings.single()
        connection.onServiceDisconnected(component)
        assertTrue(first.deaths.isEmpty())
        assertEquals(failure(SomeIpHudTransactionResult.Reason.NOT_CONNECTED), transport.fireEvent(8, byteArrayOf(1)))
        assertTrue(context.unbound.isEmpty())
        val second = RecordingBinder()
        connection.onServiceConnected(component, second)
        assertTrue(second.calls.isEmpty())
        assertEquals(SomeIpHudTransactionResult.Reply(0), transport.startService(7))
        assertEquals(1, second.descriptorReads)
        transport.close()
    }

    @Test
    fun `death from a replaced binder cannot retire the new binder`() {
        val first = RecordingBinder()
        val (transport, context) = connected(first)
        transport.startService(7)
        val oldDeath = first.deaths.single()
        val replacement = RecordingBinder()
        context.bindings.single().onServiceConnected(component, replacement)
        assertTrue(first.deaths.isEmpty())
        oldDeath.binderDied()
        assertEquals(SomeIpHudTransactionResult.Reply(0), transport.startService(7))
        assertTrue(context.unbound.isEmpty())
        transport.close()
    }

    @Test
    fun `null binding and binding death detach once and allow explicit rebind`() {
        for (nullBinding in listOf(true, false)) {
            val (transport, context) = connected(RecordingBinder())
            val connection = context.bindings.single()
            if (nullBinding) connection.onNullBinding(component) else connection.onBindingDied(component)
            connection.onBindingDied(component)
            transport.close()
            assertEquals(1, context.unbound.size)
            assertEquals(SomeIpHudBindResult.REQUESTED, transport.bind())
            assertFalse(transport.isBinderAvailable)
            transport.close()
        }
    }

    @Test
    fun `binder death detaches its registration and never replays guidance`() {
        val binder = RecordingBinder()
        val (transport, context) = connected(binder)
        transport.startService(1)
        binder.deaths.single().binderDied()
        assertFalse(transport.isBinderAvailable)
        assertTrue(binder.deaths.isEmpty())
        assertEquals(1, context.unbound.size)
        assertEquals(failure(SomeIpHudTransactionResult.Reason.NOT_CONNECTED), transport.fireEvent(2, byteArrayOf(3)))
        assertEquals(1, binder.calls.size)
        transport.bind()
        val replacement = RecordingBinder()
        context.bindings.last().onServiceConnected(component, replacement)
        assertTrue(replacement.calls.isEmpty())
        transport.close()
    }

    @Test
    fun `binder dying before death registration retires the binding without writes`() {
        val binder = RecordingBinder().apply { deathLinkFailure = true }
        val (transport, context) = connected(binder)
        assertEquals(failure(SomeIpHudTransactionResult.Reason.BINDER_DIED), transport.startService(1))
        assertEquals(1, context.unbound.size)
        assertTrue(binder.calls.isEmpty())
        assertFalse(transport.isBinderAvailable)
    }

    @Test
    fun `death during registration cannot reopen the cancelled connection`() {
        val binder = RecordingBinder().apply { duringLink = { it.binderDied() } }
        val (transport, context) = connected(binder)
        assertEquals(failure(SomeIpHudTransactionResult.Reason.SESSION_CLOSED), transport.startService(1))
        assertEquals(1, context.unbound.size)
        assertTrue(binder.calls.isEmpty())
        assertTrue(binder.deaths.isEmpty())
    }

    @Test
    fun `a descriptor refusal cannot retire a binder supplied while the lookup was running`() {
        val first = RecordingBinder("another.interface")
        val (transport, context) = connected(first)
        val replacement = RecordingBinder()
        first.duringDescriptor = {
            context.bindings.single().onServiceConnected(component, replacement)
        }
        assertEquals(failure(SomeIpHudTransactionResult.Reason.INVALID_INTERFACE), transport.startService(1))
        assertTrue(context.unbound.isEmpty())
        assertEquals(SomeIpHudTransactionResult.Reply(0), transport.startService(1))
        transport.close()
    }

    @Test
    fun `false transact and missing status do not become a zero reply`() {
        for ((handled, includeStatus, reason) in listOf(
            Triple(false, true, SomeIpHudTransactionResult.Reason.TRANSACTION_REJECTED),
            Triple(true, false, SomeIpHudTransactionResult.Reason.INVALID_REPLY)
        )) {
            val binder = RecordingBinder().apply {
                this.handled = handled
                this.includeStatus = includeStatus
            }
            val (transport, _) = connected(binder)
            assertEquals(failure(reason), transport.startService(1))
            transport.close()
        }
    }

    @Test
    fun `remote exceptions and permission refusals remain explicit failures`() {
        for ((exception, reason) in listOf(
            DeadObjectException() to SomeIpHudTransactionResult.Reason.BINDER_DIED,
            RemoteException() to SomeIpHudTransactionResult.Reason.REMOTE_EXCEPTION,
            SecurityException() to SomeIpHudTransactionResult.Reason.PERMISSION_DENIED
        )) {
            val binder = RecordingBinder().apply { transactionException = exception }
            val (transport, context) = connected(binder)
            assertEquals(failure(reason), transport.startService(1))
            if (reason != SomeIpHudTransactionResult.Reason.REMOTE_EXCEPTION) {
                assertFalse(transport.isBinderAvailable)
                assertEquals(1, context.unbound.size)
            }
            transport.close()
        }
    }

    @Test
    fun `a SecurityException encoded by the server retires the binding`() {
        val binder = RecordingBinder().apply { replyException = SecurityException("binding denied") }
        val (transport, context) = connected(binder)
        assertEquals(failure(SomeIpHudTransactionResult.Reason.PERMISSION_DENIED), transport.startService(1))
        assertFalse(transport.isBinderAvailable)
        assertEquals(1, context.unbound.size)
    }

    @Test
    fun `both Parcel buffers are released on replies refusals and exceptions`() {
        for (exit in 0..3) {
            val binder = RecordingBinder().apply {
                if (exit == 1) handled = false
                if (exit == 2) transactionException = RemoteException()
                if (exit == 3) replyException = SecurityException()
            }
            val parcels = mutableListOf<Parcel>()
            val protocol = SomeIpHudBinderProtocol(binder) {
                Parcel.obtain().also { parcels.add(it) }
            }
            val result = protocol.fireEvent(1, byteArrayOf(2, 3))
            if (exit == 0) assertEquals(SomeIpHudTransactionResult.Reply(0), result)
            else assertTrue(result is SomeIpHudTransactionResult.Failure)
            assertEquals(2, parcels.size)
            assertTrue("recycle frees both request and reply buffers", parcels.all { it.dataSize() == 0 })
        }
    }

    @Test
    fun `close is immediate during a blocked Binder call and its late reply is invalidated`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val binder = RecordingBinder().apply {
            duringTransaction = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val (transport, context) = connected(binder)
        val result = AtomicReference<SomeIpHudTransactionResult>()
        val worker = Thread { result.set(transport.startService(1)) }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            transport.close()
            assertEquals(1, context.unbound.size)
            assertFalse(transport.isBinderAvailable)
        } finally {
            release.countDown()
            worker.join(5_000)
        }
        assertFalse(worker.isAlive)
        assertEquals(failure(SomeIpHudTransactionResult.Reason.SESSION_CLOSED), result.get())
        assertEquals(failure(SomeIpHudTransactionResult.Reason.NOT_CONNECTED), transport.startService(1))
    }

    private fun connected(binder: RecordingBinder): Pair<SomeIpHudTransport, BindingContext> {
        val context = BindingContext()
        val transport = SomeIpHudTransport(context)
        assertEquals(SomeIpHudBindResult.REQUESTED, transport.bind())
        context.bindings.single().onServiceConnected(component, binder)
        return transport to context
    }

    private class BindingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val bindings = mutableListOf<ServiceConnection>()
        val unbound = mutableListOf<ServiceConnection>()
        var intent: Intent? = null
        var flags = 0
        var accept = true
        var bindException: RuntimeException? = null
        var duringBind: (() -> Unit)? = null

        override fun getApplicationContext(): Context = this

        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            intent = service
            this.flags = flags
            bindings.add(conn)
            duringBind?.invoke()
            bindException?.let { throw it }
            return accept
        }

        override fun unbindService(conn: ServiceConnection) {
            unbound.add(conn)
        }
    }

    private data class WireCall(
        val code: Int,
        val id: Long,
        val flags: Int,
        val fixedInt: Int? = null,
        val fixedLong: Long? = null,
        val explicitSize: Int? = null,
        val payload: ByteArray? = null,
        val remainingBytes: Int
    )

    private class RecordingBinder(descriptor: String = SomeIpHudBinderProtocol.DESCRIPTOR) : Binder() {
        val calls = mutableListOf<WireCall>()
        val deaths = mutableListOf<IBinder.DeathRecipient>()
        var descriptorReads = 0
        var returnCode = 0
        var handled = true
        var includeStatus = true
        var deathLinkFailure = false
        var transactionException: Exception? = null
        var descriptorException: Exception? = null
        var replyException: Exception? = null
        var duringTransaction: (() -> Unit)? = null
        var duringDescriptor: (() -> Unit)? = null
        var duringLink: ((IBinder.DeathRecipient) -> Unit)? = null

        init { attachInterface(null, descriptor) }

        override fun getInterfaceDescriptor(): String? {
            descriptorReads++
            descriptorException?.let { throw it }
            duringDescriptor?.invoke()
            return super.getInterfaceDescriptor()
        }

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            if (deathLinkFailure) throw DeadObjectException()
            deaths.add(recipient)
            duringLink?.invoke(recipient)
        }

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean =
            deaths.remove(recipient)

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            transactionException?.let { throw it }
            if (!handled) return false
            data.enforceInterface("ts.car.someip.sdk.ISomeIpServerInterface")
            calls.add(when (code) {
                4, 5 -> WireCall(code, data.readLong(), flags, remainingBytes = data.dataAvail())
                6 -> {
                    val fixedInt = data.readInt()
                    val topic = data.readLong()
                    val fixedLong = data.readLong()
                    val explicitSize = data.readInt()
                    val payload = data.createByteArray()
                    WireCall(code, topic, flags, fixedInt, fixedLong, explicitSize, payload, data.dataAvail())
                }
                else -> error("Unexpected transaction $code")
            })
            duringTransaction?.invoke()
            replyException?.let {
                requireNotNull(reply).writeException(it)
                return true
            }
            requireNotNull(reply).writeNoException()
            if (includeStatus) reply.writeInt(returnCode)
            return true
        }
    }

    private fun failure(reason: SomeIpHudTransactionResult.Reason) =
        SomeIpHudTransactionResult.Failure(reason)
}
