package com.byd.dashcast.cluster

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.*
import com.byd.dashcast.proxy.ProxyClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ProjectionAdasTransportTest {
    private class ReceiverContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val intents = mutableListOf<Intent>()
        val callbacks = mutableListOf<BroadcastReceiver>()
        var failAt = -1
        var runtimeFailure = false
        override fun sendOrderedBroadcast(intent: Intent, permission: String?, receiver: BroadcastReceiver?,
            scheduler: Handler?, initialCode: Int, initialData: String?, initialExtras: Bundle?) {
            if (intents.size == failAt) {
                if (runtimeFailure) throw IllegalStateException("test") else throw SecurityException("test")
            }
            intents += intent
            callbacks += receiver!!
        }
        fun ack(index: Int) = callbacks[index].onReceive(this, intents[index])
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun fallback(transport: ProjectionAdasTransport) = ProjectionAdasTransport::class.java
        .getDeclaredField("shellFallback").apply { isAccessible = true }.getBoolean(transport)

    @Test fun `cancel after frame one prevents frame two and duplicate ack replay`() {
        val context = ReceiverContext(); val transport = ProjectionAdasTransport(context)
        var valid = true; val results = mutableListOf<Boolean>()
        transport.frames(ProjectionAdasPlan.HIDE, 10, { valid }, results::add)
        assertEquals(1, context.intents.size)
        valid = false; context.ack(0); context.ack(0)
        assertEquals(1, context.intents.size); assertEquals(listOf(false), results)
        assertFalse(fallback(transport))
    }
    @Test fun `ordered callbacks serialize frames and do not prove rendered acceptance`() {
        val context = ReceiverContext(); val transport = ProjectionAdasTransport(context)
        val results = mutableListOf<Boolean>()
        transport.frames(ProjectionAdasPlan.HIDE, 3, { true }, results::add)
        context.ack(0); context.ack(0); context.ack(1); context.ack(2)
        assertEquals(3, context.intents.size); assertEquals(listOf(true), results)
        assertEquals("com.byd.clusterdebug", context.intents.first().`package`)
        assertNull(context.intents.first().component)
        assertEquals(Intent.FLAG_RECEIVER_FOREGROUND, context.intents.first().flags)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(listOf(true), results)
    }
    @Test fun `partial security failure never enables replay fallback`() {
        val context = ReceiverContext().apply { failAt = 1 }; val transport = ProjectionAdasTransport(context)
        val results = mutableListOf<Boolean>()
        transport.frames(ProjectionAdasPlan.HIDE, 3, { true }, results::add); context.ack(0)
        assertEquals(listOf(false), results); assertFalse(fallback(transport))
        assertEquals(1, context.intents.size)
    }
    @Test fun `only pre-delivery security failure enables the compatibility transport`() {
        val context = ReceiverContext().apply { failAt = 0 }; val transport = ProjectionAdasTransport(context)
        transport.frames(ProjectionAdasPlan.HIDE, 3, { true }) {}
        assertTrue(fallback(transport))
        val runtime = ProjectionAdasTransport(ReceiverContext().apply { failAt = 0; runtimeFailure = true })
        runtime.frames(ProjectionAdasPlan.HIDE, 3, { true }) {}
        assertFalse(fallback(runtime))
    }
    @Test fun `timeout ignores late ack and never retries`() {
        val context = ReceiverContext(); val transport = ProjectionAdasTransport(context)
        val results = mutableListOf<Boolean>()
        transport.frames(ProjectionAdasPlan.HIDE, 3, { true }, results::add)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5)); context.ack(0)
        assertEquals(listOf(false), results); assertEquals(1, context.intents.size)
        assertFalse(fallback(transport))
    }
    @Test fun `blocked read cannot consume the recovery worker`() {
        val field = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        val previous = field.get(null); val binder = Binder(); field.set(null, binder)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val restored = CountDownLatch(1)
        val transport = ProjectionAdasTransport(ReceiverContext(), { binder }, { true })
        try {
            transport.call({ true }, work = { entered.countDown(); release.await(5, TimeUnit.SECONDS); 1 }) {}
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            transport.call({ true }, work = { restored.countDown(); true }, mutation = true) {}
            assertTrue(restored.await(2, TimeUnit.SECONDS))
        } finally { release.countDown(); field.set(null, previous); idle() }
    }
    @Test fun `closed frame allowlist prevents arbitrary raw input`() {
        val transport = ProjectionAdasTransport(ReceiverContext())
        assertThrows(IllegalArgumentException::class.java) { transport.frames("00,00", 1, { true }) {} }
        assertThrows(IllegalArgumentException::class.java) { transport.frames(ProjectionAdasPlan.HIDE, 11, { true }) {} }
        assertThrows(IllegalArgumentException::class.java) { transport.command(211, { true }) {} }
    }
    @Test fun `exit zero text errors are not successful software delivery`() {
        assertTrue(AdasCommandResult.accepted("Result: Parcel(00000000 00000000)\nDASHCAST_ADAS_OK\n"))
        for (error in listOf("Error: Not found", "Permission Denial", "service not found", "Service auto_container does not exist", "Result: Parcel(ffffffff 00000000)"))
            assertFalse(error, AdasCommandResult.accepted("$error\nDASHCAST_ADAS_OK\n"))
        assertFalse(AdasCommandResult.accepted("Starting service: Intent{}"))
    }
}
