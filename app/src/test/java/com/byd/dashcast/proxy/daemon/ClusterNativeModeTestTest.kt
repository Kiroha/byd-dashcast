package com.byd.dashcast.proxy.daemon

import android.os.Binder
import android.os.Parcel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterNativeModeTestTest {
    private class Memory : ClusterNativeModeTest.Journal {
        var receipt: JSONObject? = null
        var fail = false
        override fun load(): JSONObject? = receipt
        override fun save(receipt: JSONObject) { check(!fail) { "disk unavailable" }; this.receipt = JSONObject(receipt.toString()) }
    }
    private val jobs = ArrayDeque<Runnable>()
    private val journal = Memory()
    private var now = 10000L
    private var valid = true
    private val test = ClusterNativeModeTest(Executor { jobs.addLast(it) }, journalAccess = journal,
        elapsed = { now }, pause = { now += it })
    private val nonce = "a".repeat(32)
    private val validity = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface("com.byd.dashcast.NativeModeReservation")
            reply!!.writeNoException(); reply.writeInt(if (valid) 1 else 0); return true
        }
    }
    private val commands = ArrayList<Int>()
    private val runner: (Int, () -> Unit, (Int, String, String) -> Unit) -> ClusterThemeQueryRunner.Result = { code, admit, _ ->
        admit(); commands.add(code)
        ClusterThemeQueryRunner.Result(JSONObject().put("state", "RECEIVED").put("native", 0)
            .put("attempted", true).put("detail", "Native return; QML/pixels UNKNOWN"), true)
    }
    private fun request(full: Boolean, runner: (Int, () -> Unit, (Int, String, String) -> Unit) -> ClusterThemeQueryRunner.Result = this.runner) =
        test.request(full, nonce, true, true, validity, runner)
    private fun drain() { while (jobs.isNotEmpty()) jobs.removeFirst().run() }

    @Test fun `first request waits quiet interval sends16 once and retains independent debt`() {
        request(true)
        assertTrue(journal.receipt!!.getBoolean("debt")); assertTrue(test.blocked())
        drain()
        assertEquals(listOf(16), commands); assertTrue(now >= 11500)
        assertEquals(0, test.status().getInt("native")); assertEquals("UNKNOWN", test.status().getString("physicalPixels"))
        assertTrue(test.status().getBoolean("debt")); assertFalse(test.status().getBoolean("pending"))
        assertThrows(IllegalStateException::class.java) { request(true) }
        assertThrows(IllegalStateException::class.java) { test.observedNormal(nonce) }
    }
    @Test fun `manual return cancels16 queued before GO and never replays it`() {
        request(true); request(false); drain()
        assertEquals(listOf(18), commands)
        assertTrue(test.status().getBoolean("normalSettled")); assertTrue(test.blocked())
        test.observedNormal(nonce)
        assertFalse(test.blocked()); assertTrue(test.status().getBoolean("observedByUser"))
    }
    @Test fun `return requested after16 admission runs afterwards with quiet interval`() {
        request(true) { code, admit, _ ->
            admit(); commands.add(code); request(false)
            ClusterThemeQueryRunner.Result(JSONObject().put("state", "RECEIVED").put("native", 0), true)
        }
        drain(); assertEquals(listOf(16, 18), commands); assertTrue(now >= 13000)
    }
    @Test fun `lost reservation prevents queued16 from reaching OEM`() {
        request(true); valid = false; drain()
        assertTrue(commands.isEmpty()); assertTrue(test.blocked()); assertEquals("REFUSED", test.status().getString("state"))
    }
    @Test fun `native negative return is retained without declaring physical failure or success`() {
        request(true) { code, admit, _ ->
            admit(); commands.add(code)
            ClusterThemeQueryRunner.Result(JSONObject().put("state", "RECEIVED").put("native", -1), true)
        }
        drain(); assertEquals(-1, test.status().getInt("native")); assertEquals("UNKNOWN", test.status().getString("qmlAck"))
        assertTrue(test.blocked()); assertEquals(listOf(16), commands)
    }
    @Test fun `journal failure refuses16 before helper starts and retains debt`() {
        journal.fail = true
        assertThrows(IllegalStateException::class.java) { request(true) }
        assertTrue(commands.isEmpty()); assertTrue(jobs.isEmpty()); assertTrue(test.blocked())
    }
    @Test fun `wrong nonce or an observation made before return completes cannot close debt`() {
        request(true); drain()
        assertThrows(IllegalStateException::class.java) { test.request(false, "b".repeat(32), true, true, validity, runner) }
        request(false)
        assertThrows(IllegalStateException::class.java) { test.observedNormal(nonce) }
        drain()
        assertThrows(IllegalStateException::class.java) { test.observedNormal("b".repeat(32)) }
        assertTrue(test.blocked()); test.observedNormal(nonce); assertFalse(test.blocked())
    }
    @Test fun `failed discharge persistence quarantines instead of releasing ownership`() {
        request(true); drain(); request(false); drain(); journal.fail = true
        assertThrows(IllegalStateException::class.java) { test.observedNormal(nonce) }
        assertTrue(test.blocked()); assertEquals("QUARANTINE", test.status().getString("state"))
    }
    @Test fun `recovered journal never queues a command and requires new manual return`() {
        request(true); drain()
        val recovered = ClusterNativeModeTest(Executor { jobs.addLast(it) }, journalAccess = journal,
            elapsed = { now }, pause = { now += it })
        assertTrue(recovered.blocked()); assertEquals("QUARANTINE", recovered.status().getString("state"))
        assertTrue(jobs.isEmpty()); assertEquals(listOf(16), commands)
        assertThrows(IllegalStateException::class.java) { recovered.observedNormal(nonce) }
        recovered.request(false, nonce, true, true, validity, runner); drain(); recovered.observedNormal(nonce)
        assertFalse(recovered.blocked()); assertEquals(listOf(16, 18), commands)
    }
    @Test fun `app-only recovery nonce permits explicit18 with no16 replay`() {
        request(false); drain(); assertEquals(listOf(18), commands)
        test.observedNormal(nonce); assertFalse(test.blocked())
    }
    @Test fun `unreaped helper identity is never erased by a failed recovery`() {
        request(true) { _, admit, _ -> admit(); ClusterThemeQueryRunner.Result(JSONObject().put("state", "UNAVAILABLE"), false) }
        drain(); request(false); drain()
        assertTrue(test.blocked()); assertFalse(test.status().getBoolean("normalSettled"))
        assertThrows(IllegalStateException::class.java) { test.observedNormal(nonce) }
    }
    @Test fun `corrupt helper identity fails closed without any command`() {
        journal.receipt = JSONObject().put("schema", 1).put("nonce", nonce).put("debt", true)
            .put("helperPid", 42).put("helperStart", "").put("helperToken", "")
        assertTrue(test.blocked())
        assertThrows(IllegalStateException::class.java) { request(false) }
        assertTrue(jobs.isEmpty()); assertTrue(commands.isEmpty())
    }
}
