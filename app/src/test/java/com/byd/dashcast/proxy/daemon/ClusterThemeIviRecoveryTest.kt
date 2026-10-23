package com.byd.dashcast.proxy.daemon

import android.content.Context
import android.os.Binder
import android.os.Parcel
import com.byd.dashcast.clusterlive.*
import com.byd.dashcast.proxy.ClusterThemeSession
import com.byd.dashcast.proxy.ProxyClient
import com.google.flatbuffers.FlatBufferBuilder
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterThemeIviRecoveryTest {
    private val nonce = "a23127f52c8f482985cb18fcc48ec6b2"
    private val name = ClusterThemePolicy.PREFIX + nonce + ".rcc"
    private val app = RuntimeEnvironment.getApplication()
    private fun field(key: String) = ClusterThemeLiveTest::class.java.getDeclaredField(key).apply { isAccessible = true }
    private fun set(key: String, value: Any) = field(key).set(null, value)
    private fun status() = JSONObject(ClusterThemeLiveTest.status())
    private fun journal() = JSONObject().put("schema", 3).put("transport", "android-ivi")
        .put("state", "QUARANTINE").put("filename", name).put("variant", "full-surface-test")
        .put("carrierSha256", ClusterThemeVariant.FULL_SURFACE_TEST.carrierSha256)
        .put("root", "/storage/emulated/0/.cluster/translate/").put("renderer", "")
        .put("identity", "39:3444/39:27871/39:28491").put("mutated", true)
        .put("uncertain", true).put("defaultQueried", false).put("createdDevice", 39L).put("createdInode", 27308L)
        .put("helperPid", 0).put("helperStart", "").put("helperToken", "")
    private fun selection(path: String, source: Int = 1) = ClusterThemeProtocol.Response(1001, 4,
        listOf(1, 0, 0, source, 0), listOf(ClusterThemeProtocol.UI_VERSION, "", path, "", if (path == "default") "default" else "DashCast_a"))
    private val own get() = selection(ClusterThemePolicy.HOST_DIRECTORY + name)
    private val default get() = selection("default")
    private fun bytes(reply: ClusterThemeProtocol.Response): ByteArray {
        val b = FlatBufferBuilder(512); val strings = reply.strings.map { b.createString(it) }
        b.startTable(13); b.addInt(0, reply.cmd, 0); b.addInt(1, reply.sub, 0)
        reply.ints.forEachIndexed { i, value -> b.addInt(i + 2, value, 0) }
        strings.forEachIndexed { i, value -> b.addOffset(i + 7, value, 0) }
        b.finish(b.endTable()); return b.sizedByteArray()
    }
    private fun exchange(before: ClusterThemeProtocol.Response = own, after: ClusterThemeProtocol.Response = default,
        carrierValid: Boolean = true, operation: String = "restore"): Pair<ClusterThemeLiveTest.Exchange, List<Int>> {
        var time = 0L; var queries = 0; lateinit var receiver: (Int, ByteArray) -> Unit
        val mutations = ArrayList<Int>()
        val transport = object : ClusterThemeIviExchange.Transport {
            override fun verifyIdentity() = Unit
            override fun alive() = true
            override fun register(callback: (Int, ByteArray) -> Unit): Int { receiver = callback; return 0 }
            override fun verifyCarrier() { check(carrierValid) { "Carrier generation/hash differs" } }
            override fun send(data: ByteArray): Int {
                val request = ClusterThemeProtocol.decode(data)
                if (request.cmd == 1) receiver(1000, bytes(if (++queries == 1) before else after))
                else mutations.add(request.ints[0])
                return 0
            }
            override fun close() = Unit
        }
        val result = ClusterThemeIviExchange.execute(operation, name, transport, 7000, { time }, { time += 25 })
        return ClusterThemeLiveTest.Exchange(result.native,
            result.postQueryResponses.map(ClusterThemeProtocol::decode).lastOrNull { it.cmd == 1001 && it.sub == 4 },
            result.responses.map(ClusterThemeProtocol::decode), result.error != null, result.error, result.attempted) to mutations
    }
    @Before fun reset() {
        set("initialized", true); set("journalTrusted", true); set("helperUnreaped", false)
        (field("queryActive").get(null) as AtomicBoolean).set(false)
        ClusterThemeLiveTest.applyRecoveredJournal(ClusterThemeJournal.recover(journal()))
        app.getSharedPreferences("rcc_cluster_operational_debt", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun clear() {
        (field("active").get(null) as AtomicBoolean).set(false)
        app.getSharedPreferences("rcc_cluster_operational_debt", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun `build702 source1 journal recovers through one ordered default request and fresh OEM observation`() {
        assertTrue(status().getBoolean("uncertain")); assertFalse(field("loadSettled").getBoolean(null))
        val (restored, mutations) = exchange()
        assertEquals(listOf(1), mutations); assertFalse(restored.unknown)
        ClusterThemeLiveTest.recordRestoreResult(own, restored, false)
        assertEquals("DEFAULT_QUERIED", status().getString("state"))
        assertTrue(status().getBoolean("iviRecoverySettled")); assertTrue(status().getBoolean("uncertain"))
        var deleted = 0
        val written = ArrayList<String>()
        ClusterThemeLiveTest.completeRecovery({ deleted++; true }, { written.add(it) })
        assertEquals(0, deleted); assertTrue(status().getBoolean("rccDebt"))
        assertFalse(ClusterThemeLiveTest.acceptOemObservation(name.replace("a231", "bbbb")))
        assertTrue(ClusterThemeLiveTest.acceptOemObservation(name))
        ClusterThemeLiveTest.completeRecovery({ deleted++; true }, { written.add(it) })
        assertEquals(1, deleted); assertEquals(listOf("DEFAULT_QUERIED", "CLEAN"), written)
        val safe = status()
        assertEquals("CLEAN", safe.getString("state")); assertFalse(safe.getBoolean("rccDebt"))
        assertTrue(safe.getBoolean("uncertain")); assertEquals(nonce, safe.getString("rccSafeNonce"))
        // The app clears only matching operational debt from this fresh daemon receipt.
        val binderField = ProxyClient::class.java.getDeclaredField("sBinder").apply { isAccessible = true }
        val versionField = ProxyClient::class.java.getDeclaredField("sDaemonVer").apply { isAccessible = true }
        val prior = binderField.get(null); val version = versionField.get(null)
        ClusterThemeProjectionDebt.begin(app, nonce)
        binderField.set(null, object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(ProxyDaemonContract.TXN_CLUSTER_LIVE_TEST, code)
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR); assertEquals(0, data.readInt())
                reply!!.writeNoException(); reply.writeString(safe.toString()); return true
            }
        }); versionField.set(null, "37")
        try { ClusterThemeSession.pin(app).status(); assertFalse(ClusterThemeProjectionDebt.blocked(app)) }
        finally { binderField.set(null, prior); versionField.set(null, version) }
    }
    @Test fun `already default without an ordered proof cannot close unresolved build702 debt`() {
        val (queried, mutations) = exchange(default, operation = "query")
        ClusterThemeLiveTest.recordRestoreResult(default, queried, false)
        assertTrue(mutations.isEmpty()); assertFalse(status().getBoolean("iviRecoverySettled"))
        assertEquals("QUARANTINE", status().getString("state"))
        assertTrue(ClusterThemeLiveTest.acceptOemObservation(name))
        ClusterThemeLiveTest.completeRecovery({ fail("Unsettled load must retain its carrier"); false }, {})
        assertTrue(status().getBoolean("rccDebt"))
    }
    @Test fun `a durable completed recovery checkpoint survives restart but still needs fresh query and observation`() {
        ClusterThemeLiveTest.applyRecoveredJournal(ClusterThemeJournal.recover(journal().put("iviRecoverySettled", true)))
        assertTrue(status().getBoolean("iviRecoverySettled")); assertFalse(status().getBoolean("defaultQueried"))
        assertFalse(ClusterThemeLiveTest.acceptOemObservation(name))
        val (queried, mutations) = exchange(default, operation = "query")
        ClusterThemeLiveTest.recordRestoreResult(default, queried, true)
        assertTrue(mutations.isEmpty()); assertTrue(status().getBoolean("iviRecoverySettled"))
        assertTrue(status().getBoolean("rccDebt")); assertTrue(ClusterThemeLiveTest.acceptOemObservation(name))
        ClusterThemeLiveTest.completeRecovery({ true }, {})
        assertEquals("CLEAN", status().getString("state"))
    }
    @Test fun `foreign selection changed carrier and failed default response retain debt`() {
        val cases = listOf(exchange(own.copy(strings = own.strings.toMutableList().apply { set(2, ClusterThemePolicy.HOST_DIRECTORY + name.replace("a231", "bbbb")) })),
            exchange(carrierValid = false), exchange(after = own))
        for ((restored, _) in cases) {
            ClusterThemeLiveTest.applyRecoveredJournal(ClusterThemeJournal.recover(journal()))
            ClusterThemeLiveTest.recordRestoreResult(own, restored, false)
            assertFalse(status().getBoolean("iviRecoverySettled")); assertTrue(status().getBoolean("rccDebt"))
        }
        assertTrue(cases[0].second.isEmpty()); assertTrue(cases[1].second.isEmpty())
        assertEquals(listOf(1), cases[2].second)
    }
    @Test fun `ACK3 with a fresh default query retains recovery debt and exposes its numeric cause`() {
        val negative = ClusterThemeProtocol.Response(1005, 0, listOf(3, 0, 0x2330, 0, 0), List(5) { "" })
        val restored = ClusterThemeLiveTest.Exchange(0, default, listOf(negative), true,
            negative.resourceFailureSummary(), true)
        ClusterThemeLiveTest.recordRestoreResult(own, restored, false)
        val receipt = status()
        assertEquals("QUARANTINE", receipt.getString("state")); assertTrue(receipt.getBoolean("rccDebt"))
        assertFalse(receipt.getBoolean("defaultQueried")); assertFalse(receipt.getBoolean("iviRecoverySettled"))
        assertTrue(receipt.getString("detail").contains("RESOURCE_IS_SAME"))
        assertTrue(receipt.getString("detail").contains("0x2330"))
        assertFalse(ClusterThemeLiveTest.acceptOemObservation(name))
        ClusterThemeLiveTest.completeRecovery({ fail("Negative OEM recovery cannot delete its carrier"); false }, {})
        assertTrue(status().getBoolean("rccDebt"))
    }
    @Test fun `cleanup failure preserves ordered proof but requires a fresh query and OEM answer on retry`() {
        val (restored, _) = exchange()
        ClusterThemeLiveTest.recordRestoreResult(own, restored, false)
        assertTrue(ClusterThemeLiveTest.acceptOemObservation(name))
        assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.completeRecovery({ false }, {}) }
        assertTrue(status().getBoolean("iviRecoverySettled")); assertTrue(status().getBoolean("rccDebt"))
        // Persisted checkpoint plus a new daemon discards every old visual/default observation.
        ClusterThemeLiveTest.applyRecoveredJournal(ClusterThemeJournal.recover(journal().put("iviRecoverySettled", true)))
        assertFalse(ClusterThemeLiveTest.acceptOemObservation(name))
        val (queried, _) = exchange(default, operation = "query")
        ClusterThemeLiveTest.recordRestoreResult(default, queried, true)
        assertTrue(ClusterThemeLiveTest.acceptOemObservation(name))
        ClusterThemeLiveTest.completeRecovery({ true }, {})
        assertEquals("CLEAN", status().getString("state"))
    }
    @Test fun `recovery proof cannot migrate to the strict renderer transport`() {
        val strict = journal().put("schema", 2).apply { remove("transport"); remove("iviRecoverySettled") }
        ClusterThemeLiveTest.applyRecoveredJournal(ClusterThemeJournal.recover(strict))
        val (restored, _) = exchange()
        ClusterThemeLiveTest.recordRestoreResult(own, restored, false)
        assertFalse(status().getBoolean("iviRecoverySettled")); assertEquals("QUARANTINE", status().getString("state"))
    }
    private fun retiredJournal(moved: Boolean = true) = journal().put("schema", 4).put("retirement",
        ClusterThemeRetirement(".dashcast-retired-" + "b".repeat(32), 39, 99999, moved).json())
    @Test fun `retired recovery queries only then needs a new matching OEM observation to close original nonce`() {
        ClusterThemeLiveTest.applyRecoveredJournal(ClusterThemeJournal.recover(retiredJournal()))
        assertEquals("QUARANTINE", status().getString("state")); assertTrue(status().getBoolean("rccDebt"))
        assertFalse(status().getBoolean("retirementAvailable")); assertFalse(ClusterThemeLiveTest.acceptOemObservation(name))
        val (queried, mutations) = exchange(default, operation = "query")
        assertTrue(mutations.isEmpty()); ClusterThemeLiveTest.recordRetiredQuery(queried)
        assertEquals("DEFAULT_QUERIED", status().getString("state")); assertTrue(status().getBoolean("rccDebt"))
        var preservationChecks = 0
        ClusterThemeLiveTest.completeRecovery({ preservationChecks++; true }, {})
        assertEquals(0, preservationChecks)
        assertFalse(ClusterThemeLiveTest.acceptOemObservation(name.replace("a231", "bbbb")))
        assertTrue(ClusterThemeLiveTest.acceptOemObservation(name))
        ClusterThemeLiveTest.completeRecovery({ preservationChecks++; true }, {})
        assertEquals(1, preservationChecks); assertEquals("CLEAN", status().getString("state"))
        assertFalse(status().getBoolean("rccDebt")); assertEquals(nonce, status().getString("rccSafeNonce"))
        assertTrue(status().getJSONObject("retirement").getBoolean("moved"))
        assertTrue(status().getString("detail").contains("preserved unchanged"))
    }
    @Test fun `retired own foreign unknown and failed queries retain debt and incomplete intent cannot mint proof`() {
        val foreign = own.copy(strings = own.strings.toMutableList().apply { set(2, "foreign.rcc") })
        for (result in listOf(ClusterThemeLiveTest.Exchange(0, own, emptyList(), false),
            ClusterThemeLiveTest.Exchange(0, foreign, emptyList(), false),
            ClusterThemeLiveTest.Exchange(0, default, emptyList(), true, "callback invalid"),
            ClusterThemeLiveTest.Exchange(-1, default, emptyList(), false))) {
            ClusterThemeLiveTest.applyRecoveredJournal(ClusterThemeJournal.recover(retiredJournal()))
            ClusterThemeLiveTest.recordRetiredQuery(result)
            assertFalse(status().getBoolean("defaultQueried")); assertFalse(status().getBoolean("iviRecoverySettled"))
            assertTrue(status().getBoolean("rccDebt")); assertFalse(ClusterThemeLiveTest.acceptOemObservation(name))
        }
        ClusterThemeLiveTest.applyRecoveredJournal(ClusterThemeJournal.recover(retiredJournal(false)))
        assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.recordRetiredQuery(ClusterThemeLiveTest.Exchange(0, default, emptyList(), false)) }
        assertTrue(status().getBoolean("rccDebt"))
    }
    @Test fun `retirement cannot be admitted without a sealed negative receipt or while another lane is active`() {
        val same = ClusterThemeProtocol.Response(1005, 0, listOf(3, 0, 0x2330, 0, 0), List(5) { "" })
        val persisted = own.copy(ints = listOf(1, 0, 0, 0, 0))
        val invalid = ClusterThemeLiveTest.Exchange(0, persisted, listOf(same), true, "SAME and invalid callback", true)
        ClusterThemeLiveTest.recordRestoreResult(persisted, invalid, false)
        assertFalse(status().getBoolean("retirementAvailable"))
        val valid = invalid.copy(retirementEligible = true)
        ClusterThemeLiveTest.recordRestoreResult(persisted, valid, false)
        assertTrue(status().getBoolean("retirementAvailable"))
        (field("queryActive").get(null) as AtomicBoolean).set(true)
        assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.requestAndroidRetirement(nonce, true, true, Binder()) }
        (field("queryActive").get(null) as AtomicBoolean).set(false)
        set("retirementEligibleUntil", 0L)
        assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.requestAndroidRetirement(nonce, true, true, Binder()) }
        assertEquals("QUARANTINE", status().getString("state"))
    }
}
