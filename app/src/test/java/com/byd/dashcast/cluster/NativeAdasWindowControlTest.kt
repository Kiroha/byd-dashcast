package com.byd.dashcast.cluster

import android.app.Application
import com.byd.dashcast.cluster.NativeAdasWindowControl.Action
import com.byd.dashcast.cluster.NativeAdasWindowControl.Result
import com.byd.dashcast.util.AppLogger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class NativeAdasWindowControlTest {
    private val events = mutableListOf<String>()
    private val probe = ManualExecutor()
    private val dispatch = ManualExecutor()
    private var available = true
    private var original = 1
    private var saved: Int? = null
    private var persist = true
    private var read: (() -> Unit)? = null
    private var save: (() -> Unit)? = null
    private var send: (() -> Unit)? = null
    private val device = object : NativeAdasWindowControl.Device {
        override fun available(): Boolean { events += "available"; return available }
        override fun originalValue(): Int { events += "read"; read?.invoke(); return original }
        override fun send(frame: String) { events += frame; send?.invoke() }
    }
    private val recovery = object : NativeAdasWindowControl.Recovery {
        override fun value() = saved
        override fun saveOriginal(): Boolean {
            events += "save"; save?.invoke()
            if (persist) saved = 1
            return persist
        }
    }
    private val control = NativeAdasWindowControl(device, recovery, probe, dispatch)

    @Test fun `construction and reopening never emit commands`() {
        NativeAdasWindowControl(device, recovery, probe, dispatch)
        saved = 1
        assertTrue(events.isEmpty())
        assertTrue(probe.tasks.isEmpty())
        assertTrue(dispatch.tasks.isEmpty())
    }

    @Test fun `hide persists the verified original before its exact single downlink`() {
        val results = mutableListOf<Result>()
        control.request(Action.HIDE, results::add); probe.run()
        assertEquals(listOf("available", "read", "save", "23,50,00,08,01,00"), events)
        assertEquals(1, saved)
        assertEquals(listOf(Result.HIDE_SENT), results)
    }

    @Test fun `every hide queries again and refuses zero unsupported modes and SDK sentinels`() {
        saved = 1
        for (value in listOf(0, 2, -2147482648, -999999999)) {
            original = value; events.clear()
            val results = mutableListOf<Result>()
            control.request(Action.HIDE, results::add); probe.run()
            assertEquals(listOf("available", "read"), events)
            assertEquals(listOf(Result.ORIGINAL_UNKNOWN), results)
        }
    }

    @Test fun `restore always sends captured one without querying unchanged cache or proxy`() {
        saved = 1; original = 1
        read = { error("The disconnected proxy must never be queried for recovery") }
        repeat(2) {
            val results = mutableListOf<Result>()
            control.request(Action.RESTORE, results::add); dispatch.run()
            assertEquals(listOf(Result.RESTORE_SENT), results)
        }
        assertEquals(listOf("available", "23,50,00,08,01,01", "available", "23,50,00,08,01,01"), events)
        assertEquals(1, saved) // Android delivery is not a rendered-state ACK; manual retry remains possible.
    }

    @Test fun `missing recovery and unavailable capability never write`() {
        val results = mutableListOf<Result>()
        control.request(Action.RESTORE, results::add); dispatch.run()
        available = false
        control.request(Action.HIDE, results::add); probe.run()
        assertEquals(listOf(Result.RECOVERY_MISSING, Result.UNAVAILABLE), results)
        assertEquals(listOf("available", "available"), events)
    }

    @Test fun `failed persistence forbids masking and an uncertain send retains recovery`() {
        val results = mutableListOf<Result>()
        persist = false
        control.request(Action.HIDE, results::add); probe.run()
        assertEquals(listOf("available", "read", "save"), events)
        assertNull(saved)
        persist = true; send = { error("delivery uncertain") }
        control.request(Action.HIDE, results::add); probe.run()
        assertEquals(listOf(Result.RECOVERY_FAILED, Result.SEND_FAILED), results)
        assertEquals(1, saved)
        assertEquals(1, events.count { it == NativeAdasWindowControl.HIDE_FRAME })
    }

    @Test fun `query failure never persists or emits`() {
        val before = AppLogger.getEntries().lastOrNull()?.sequence ?: 0L
        read = { throw NoSuchMethodException("private SDK details and identifiers") }
        val results = mutableListOf<Result>()
        control.request(Action.HIDE, results::add); probe.run()
        assertEquals(listOf(Result.SEND_FAILED), results)
        assertEquals(listOf("available", "read"), events)
        assertNull(saved)
        val logs = AppLogger.getEntries().filter { it.sequence > before && it.tag == "NativeAdasWindow" }
        assertTrue(logs.any { it.message == "stage=original_read exception=NoSuchMethodException" })
        assertFalse(logs.any { it.message.contains("private SDK details") })
    }

    @Test fun `multiple activities share the same in flight guard`() {
        val results = mutableListOf<Result>()
        control.request(Action.HIDE) { }
        control.request(Action.RESTORE, results::add)
        assertEquals(listOf(Result.BUSY), results)
        assertTrue(dispatch.tasks.isEmpty())
        probe.run()
    }

    @Test fun `cancelling a queued request prevents every probe and physical write`() {
        var callbacks = 0
        val request = control.request(Action.HIDE) { callbacks++ }
        request.cancel(); probe.run()
        assertTrue(events.isEmpty()); assertEquals(0, callbacks)
    }

    @Test fun `held query cancellation permits restore and its late result never hides`() {
        saved = 1
        val results = mutableListOf<Result>()
        val request = control.request(Action.HIDE) { fail("cancelled callback") }
        read = {
            request.cancel()
            control.request(Action.RESTORE, results::add)
            dispatch.run() // Recovery completes while the original SDK call is still held.
        }
        probe.run()
        assertEquals(listOf("available", "read", "available", NativeAdasWindowControl.RESTORE_FRAME), events)
        assertEquals(listOf(Result.RESTORE_SENT), results)
    }

    @Test fun `retired probe cannot clear a newer restoration ownership slot`() {
        saved = 1
        val results = mutableListOf<Result>()
        val old = control.request(Action.HIDE) { fail("cancelled callback") }
        read = { old.cancel(); control.request(Action.RESTORE, results::add) }
        probe.run() // Restore is pending in the other lane when this old probe completes.
        control.request(Action.HIDE, results::add)
        assertEquals(listOf(Result.BUSY), results)
        dispatch.run()
        assertEquals(listOf(Result.BUSY, Result.RESTORE_SENT), results)
        assertFalse(events.contains(NativeAdasWindowControl.HIDE_FRAME))
    }

    @Test fun `cancel during persistence keeps recovery but suppresses dispatch`() {
        val request = control.request(Action.HIDE) { fail("cancelled callback") }
        save = { request.cancel() }
        probe.run()
        assertEquals(1, saved)
        assertFalse(events.contains(NativeAdasWindowControl.HIDE_FRAME))
    }

    @Test fun `already started dispatch remains serialized even if dialog closes`() {
        val results = mutableListOf<Result>()
        val request = control.request(Action.HIDE) { fail("closed dialog callback") }
        send = { request.cancel(); control.request(Action.RESTORE, results::add) }
        probe.run()
        assertEquals(listOf(Result.BUSY), results)
        assertEquals(1, events.count { it == NativeAdasWindowControl.HIDE_FRAME })
    }

    @Test fun `bounded probe rejection leaves the independent recovery lane usable`() {
        saved = 1
        val rejected = NativeAdasWindowControl(device, recovery,
            Executor { throw RejectedExecutionException() }, dispatch)
        val results = mutableListOf<Result>()
        rejected.request(Action.HIDE, results::add)
        rejected.request(Action.RESTORE, results::add); dispatch.run()
        assertEquals(listOf(Result.UNAVAILABLE, Result.RESTORE_SENT), results)
        assertFalse(events.contains("read"))
    }

    internal class ManualExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun run() { tasks.removeFirst().run() }
    }
}
