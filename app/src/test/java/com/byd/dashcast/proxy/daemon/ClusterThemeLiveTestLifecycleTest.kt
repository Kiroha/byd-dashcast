package com.byd.dashcast.proxy.daemon

import com.byd.dashcast.clusterlive.ClusterThemePolicy
import com.byd.dashcast.clusterlive.ClusterThemeVariant
import com.byd.dashcast.clusterlive.ClusterRendererDiscovery
import com.byd.dashcast.clusterlive.rendererInputStreams
import android.system.ErrnoException
import android.system.OsConstants
import android.os.Binder
import android.os.Parcel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.lang.reflect.InvocationTargetException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterThemeLiveTestLifecycleTest {
    @org.robolectric.annotation.Implements(android.system.Os::class)
    class AdmissionOs {
        companion object {
            @JvmStatic @org.robolectric.annotation.Implementation
            fun fstat(fd: java.io.FileDescriptor) = android.system.StructStat(1L, 9L, OsConstants.S_IFREG or 420,
                1L, 2000, 2000, 0L, 7L, 0L, 0L, 0L, 4096L, 1L)
        }
    }
    private fun field(name: String) = ClusterThemeLiveTest::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any) { field(name).set(null, value) }
    private fun flag(name: String) = field(name).get(null) as AtomicBoolean
    private fun count(name: String) = field(name).get(null) as AtomicLong
    private val worker get() = field("worker").get(null) as ExecutorService
    private val name = ClusterThemePolicy.PREFIX + "a".repeat(32) + ".rcc"
    private fun drain() { val done = CountDownLatch(1); worker.execute { done.countDown() }; assertTrue(done.await(3, TimeUnit.SECONDS)) }
    @Before fun reset() {
        drain(); set("initialized", true); set("journalTrusted", true); set("state", "PREFLIGHT")
        set("filename", name); set("mutated", false); set("uncertain", false); set("root", "")
        set("renderer", ""); set("deadline", 0L); set("markerObserved", false); set("oemObserved", false); set("carrierRemoved", false); set("createdInode", -1L); set("createdDevice", -1L)
        set("themeObserved", false); set("variant", ClusterThemeVariant.MARKER)
        set("defaultQueried", false); set("loadSettled", false); set("iviRecoverySettled", false); set("helperPid", 0)
        set("productionModeCommands", 0); field("retirement").set(null, null); field("cornerVerification").set(null, null)
        set("transport", com.byd.dashcast.clusterlive.ClusterThemeTransport.STRICT_RENDERER)
        flag("active").set(true); flag("queryActive").set(false); flag("restoreRequested").set(false)
        set("oemQuery", JSONObject()); set("helperUnreaped", false)
        count("generation").set(10); count("restoring").set(-1)
        (field("observationPending").get(null) as AtomicInteger).set(0)
    }
    @After fun finish() { drain(); flag("active").set(false) }
    private fun reservation() = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface("com.byd.dashcast.RccReservation"); assertEquals(1, code)
            reply!!.writeNoException(); reply.writeInt(1); return true
        }
    }
    private fun cornerSession() {
        set("variant", ClusterThemeVariant.CORNER_MARKER); set("mutated", true); set("uncertain", true)
        set("state", "QUARANTINE"); set("transport", com.byd.dashcast.clusterlive.ClusterThemeTransport.ANDROID_IVI)
    }
    private fun cornerSelection(source: Int = 0, path: String = ClusterThemePolicy.HOST_DIRECTORY + name) =
        com.byd.dashcast.clusterlive.ClusterThemeProtocol.Response(1001, 4, listOf(1, 0, 0, source, 0),
            listOf(com.byd.dashcast.clusterlive.ClusterThemeProtocol.UI_VERSION, "", path, "", "DashCast_a"))
    @Test fun `recovered corner read only query creates fresh visual round and yes never settles load or recovery debt`() {
        for (profile in listOf(ClusterThemeVariant.CORNER_MARKER, ClusterThemeVariant.CORNER_MARKER_21F7735C)) for (source in listOf(0, 1)) {
            reset()
            val saved = JSONObject().put("schema", 3).put("state", "SELECTION_PENDING_OEM_APPLY")
                .put("transport", "android-ivi").put("variant", profile.id).put("carrierSha256", profile.carrierSha256)
                .put("filename", name).put("mutated", true).put("helperPid", 0).put("helperStart", "").put("helperToken", "")
                .put("root", "/storage/emulated/0/.cluster/translate/").put("identity", "1:2/1:3/1:4").put("createdDevice", 1).put("createdInode", 9)
            ClusterThemeLiveTest.applyRecoveredJournal(com.byd.dashcast.clusterlive.ClusterThemeJournal.recover(saved))
            assertTrue(JSONObject(ClusterThemeLiveTest.status()).isNull("cornerVerification"))
            var queries = 0; var carrierChecks = 0
            ClusterThemeLiveTest.requestCornerVerification("a".repeat(32), true, true, reservation(), { carrierChecks++ }) {
                queries++; ClusterThemeLiveTest.Exchange(0, cornerSelection(source), emptyList(), false)
            }; drain()
            val before = JSONObject(ClusterThemeLiveTest.status())
            val round = before.getJSONObject("cornerVerification").getString("round")
            assertTrue(before.getJSONObject("cornerVerification").getBoolean("available")); assertEquals(1, queries); assertEquals(2, carrierChecks)
            assertTrue(before.getString("trace").contains("source=$source selectionOnly=true nativePropertyEmpty=${source == 1} pixels=UNKNOWN"))
            ClusterThemeLiveTest.observeCorners(name, round)
            val after = JSONObject(ClusterThemeLiveTest.status())
            assertTrue(after.getBoolean("markerObservedByUser")); assertTrue(after.getBoolean("uncertain")); assertTrue(after.getBoolean("rccDebt"))
            assertFalse(field("loadSettled").getBoolean(null)); assertFalse(after.getBoolean("iviRecoverySettled")); assertFalse(after.getBoolean("defaultQueried"))
            assertEquals("QUARANTINE", after.getString("state")); assertFalse(after.getJSONObject("cornerVerification").getBoolean("available"))
            assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.beginProductionModeCommand() }
            assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.observeCorners(name, round) }
            ClusterThemeLiveTest.applyRecoveredJournal(com.byd.dashcast.clusterlive.ClusterThemeJournal.recover(saved))
            assertTrue(JSONObject(ClusterThemeLiveTest.status()).isNull("cornerVerification"))
            assertFalse(JSONObject(ClusterThemeLiveTest.status()).getBoolean("markerObservedByUser"))
        }
    }
    @Config(sdk = [29], shadows = [AdmissionOs::class])
    @Test fun `recovered historical full surface quarantine rejects current cohort start before any worker or staging`() {
        val old = ClusterThemeVariant.FULL_SURFACE_TEST
        val saved = JSONObject().put("schema", 3).put("state", "QUARANTINE")
            .put("transport", "android-ivi").put("variant", old.id).put("carrierSha256", old.carrierSha256)
            .put("filename", name).put("mutated", true).put("helperPid", 0).put("helperStart", "").put("helperToken", "")
            .put("root", "/storage/emulated/0/.cluster/translate/").put("identity", "1:2/1:3/1:4")
            .put("createdDevice", 1).put("createdInode", 9)
        ClusterThemeLiveTest.applyRecoveredJournal(com.byd.dashcast.clusterlive.ClusterThemeJournal.recover(saved))
        val before = JSONObject(ClusterThemeLiveTest.status())
        val next = "b".repeat(32)
        val file = java.io.File.createTempFile("rcc-existing-debt", ".rcc").apply { writeText("fixture") }
        try {
            val source = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val receipt = JSONObject(ClusterThemeLiveTest.startAndroid(source, true, true, next, reservation(), ClusterThemeVariant.CORNER_MARKER_21F7735C))
            assertEquals(next, receipt.getString("rccRejectedNonce")); drain()
            val after = JSONObject(ClusterThemeLiveTest.status())
            for (key in listOf("state", "filename", "variant", "carrierSha256", "phase", "trace", "rccDebt", "mutated", "defaultQueried"))
                assertEquals(key, before.get(key), after.get(key))
            assertEquals("QUARANTINE", after.getString("state")); assertTrue(after.getBoolean("rccDebt"))
            assertEquals(9L, field("createdInode").getLong(null)); assertEquals(0, field("helperPid").getInt(null))
            assertEquals(next, receipt.getString("rccRejectedNonce"))
        } finally { file.delete() }
    }
    @Test fun `corner verification refuses unsupported source foreign transient missing and changed carriers without mutation`() {
        for (bad in 0..3) {
            cornerSession(); var queries = 0; var checks = 0
            ClusterThemeLiveTest.requestCornerVerification("a".repeat(32), true, true, reservation(), {
                checks++; if (bad == 3 && checks == 2) error("Carrier generation changed")
            }) {
                queries++
                ClusterThemeLiveTest.Exchange(if (bad == 2) null else 0,
                    cornerSelection(if (bad == 0) 2 else 0, if (bad == 1) "default" else ClusterThemePolicy.HOST_DIRECTORY + name), emptyList(), bad == 2)
            }; drain()
            val status = JSONObject(ClusterThemeLiveTest.status())
            assertTrue(status.isNull("cornerVerification")); assertTrue(status.getBoolean("rccDebt")); assertTrue(status.getBoolean("uncertain"))
            assertEquals(1, queries); assertFalse(field("loadSettled").getBoolean(null))
        }
    }
    @Test fun `real source1 own selection with ACK4 and no ACK2 permits only user observation and keeps all debts`() {
        cornerSession()
        val configAck = com.byd.dashcast.clusterlive.ClusterThemeProtocol.Response(1005, 0, listOf(4, 0, 0, 0, 0), List(5) { "" })
        ClusterThemeLiveTest.recordLoadEvidence(ClusterThemeLiveTest.Exchange(0, cornerSelection(1), listOf(configAck), false, attempted = true))
        val prior = JSONObject(ClusterThemeLiveTest.status())
        assertEquals("OWN_PATH_REPORTED", prior.getJSONObject("loadEvidence").getString("selection"))
        assertEquals("NOT_RECEIVED", prior.getJSONObject("loadEvidence").getString("ack2"))
        var queries = 0
        ClusterThemeLiveTest.requestCornerVerification("a".repeat(32), true, true, reservation(), {}) {
            queries++; ClusterThemeLiveTest.Exchange(0, cornerSelection(1), emptyList(), false)
        }; drain()
        val verified = JSONObject(ClusterThemeLiveTest.status())
        assertTrue(verified.getJSONObject("cornerVerification").getBoolean("available"))
        assertFalse(verified.getBoolean("defaultQueried")); assertEquals(1, queries)
        ClusterThemeLiveTest.observeCorners(name, verified.getJSONObject("cornerVerification").getString("round"))
        val after = JSONObject(ClusterThemeLiveTest.status())
        assertTrue(after.getBoolean("markerObservedByUser"))
        for (key in listOf("state", "uncertain", "rccDebt", "defaultQueried", "iviRecoverySettled", "retirementAvailable", "oemObservedByUser"))
            assertEquals(key, prior.get(key), after.get(key))
        assertFalse(field("loadSettled").getBoolean(null))
        assertEquals("NOT_RECEIVED", after.getJSONObject("loadEvidence").getString("ack2"))
        assertEquals("USER_REPORTED", after.getJSONObject("loadEvidence").getString("pixels"))
    }
    @Test fun `existing recovery proofs and unreaped helper refuse corner query before worker`() {
        for (proof in listOf("defaultQueried", "iviRecoverySettled", "oemObserved", "carrierRemoved", "helperUnreaped")) {
            cornerSession(); set(proof, true); var queries = 0
            assertThrows(IllegalStateException::class.java) {
                ClusterThemeLiveTest.requestCornerVerification("a".repeat(32), true, true, reservation(), {}) {
                    queries++; error("No query allowed")
                }
            }
            assertEquals(0, queries); assertTrue(field(proof).getBoolean(null)); assertTrue(flag("active").get())
            set(proof, false)
        }
    }
    @Test fun `expired round and replaced reservation refuse corner answer without clearing debt`() {
        cornerSession()
        ClusterThemeLiveTest.requestCornerVerification("a".repeat(32), true, true, reservation(), {}) {
            ClusterThemeLiveTest.Exchange(0, cornerSelection(), emptyList(), false)
        }; drain()
        val verified = field("cornerVerification").get(null) as com.byd.dashcast.clusterlive.ClusterCornerVerification
        field("projectionValidity").set(null, Binder())
        assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.observeCorners(name, verified.round) }
        field("projectionValidity").set(null, reservation())
        field("cornerVerification").set(null, verified.copy(expiresElapsedMs = 0))
        assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.observeCorners(name, verified.round) }
        assertTrue(flag("active").get()); assertFalse(field("markerObserved").getBoolean(null)); assertTrue(field("uncertain").getBoolean(null))
    }
    @Test fun `manual restore during blocked readonly query invalidates round and remains queued`() {
        cornerSession(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        ClusterThemeLiveTest.requestCornerVerification("a".repeat(32), true, true, reservation(), {}) {
            entered.countDown(); release.await(3, TimeUnit.SECONDS)
            ClusterThemeLiveTest.Exchange(0, cornerSelection(), emptyList(), false)
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertTrue(JSONObject(ClusterThemeLiveTest.status()).getBoolean("recoveryPending"))
        ClusterThemeLiveTest.requestRestore(name)
        assertTrue(flag("restoreRequested").get()); assertTrue(JSONObject(ClusterThemeLiveTest.status()).isNull("cornerVerification"))
        set("journalTrusted", false) // Bound this fixture before any real storage/helper operation.
        release.countDown(); drain(); drain()
        assertEquals(-1L, count("restoring").get()); assertTrue(flag("restoreRequested").get())
        assertFalse(JSONObject(ClusterThemeLiveTest.status()).getBoolean("recoveryPending"))
        assertTrue(JSONObject(ClusterThemeLiveTest.status()).getString("detail").contains("Journal identity/mutation state unknown"))
        assertTrue(flag("active").get()); assertTrue(JSONObject(ClusterThemeLiveTest.status()).isNull("cornerVerification"))
    }
    @Test fun `corner admitted load and failed durable journal suppress stale30and90second watchdog decisions`() {
        for (profile in listOf(ClusterThemeVariant.CORNER_MARKER, ClusterThemeVariant.CORNER_MARKER_21F7735C)) {
            reset()
            set("variant", profile); set("deadline", Long.MAX_VALUE)
            ClusterThemeLiveTest.markLoadStarted {}
            set("deadline", 1L) // A stale timer observation cannot cause an automatic recovery after GO.
            ClusterThemeLiveTest.watchdogTick(30000); ClusterThemeLiveTest.watchdogTick(90000)
            assertFalse(flag("restoreRequested").get()); assertTrue(flag("active").get()); assertTrue(field("mutated").getBoolean(null))
            set("mutated", false); set("deadline", Long.MAX_VALUE)
            assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.markLoadStarted { error("fsync failed") } }
            ClusterThemeLiveTest.watchdogTick(Long.MAX_VALUE)
            assertFalse(flag("restoreRequested").get()); assertTrue(flag("active").get())
        }
    }
    @Test fun `load receipts distinguish selection config ack registration and user pixels without changing admission`() {
        val selection = com.byd.dashcast.clusterlive.ClusterThemeProtocol.Response(1001, 4,
            listOf(1, 0, 0, 0, 0), listOf(com.byd.dashcast.clusterlive.ClusterThemeProtocol.UI_VERSION, "", ClusterThemePolicy.HOST_DIRECTORY + name, "", "DashCast_a"))
        val configAck = com.byd.dashcast.clusterlive.ClusterThemeProtocol.Response(1005, 0, listOf(4, 0, 0, 0, 0), listOf("", "", "", "", ""))
        ClusterThemeLiveTest.recordLoadEvidence(ClusterThemeLiveTest.Exchange(0, selection, listOf(configAck), false, attempted = true))
        val first = JSONObject(ClusterThemeLiveTest.status())
        assertEquals("OWN_PATH_REPORTED", first.getJSONObject("loadEvidence").getString("selection"))
        assertEquals("NOT_RECEIVED", first.getJSONObject("loadEvidence").getString("ack2"))
        assertEquals("UNKNOWN", first.getJSONObject("loadEvidence").getString("pixels"))
        assertFalse(first.getBoolean("markerObservedByUser")); assertFalse(first.getBoolean("mutated")); assertTrue(first.getBoolean("rccDebt"))
        val registered = configAck.copy(ints = listOf(2, 0, 0, 0, 0), strings = listOf(ClusterThemePolicy.HOST_DIRECTORY + name, "", "", "", ""))
        ClusterThemeLiveTest.recordLoadEvidence(ClusterThemeLiveTest.Exchange(0, selection, listOf(registered), false, attempted = true))
        val second = JSONObject(ClusterThemeLiveTest.status()).getJSONObject("loadEvidence")
        assertEquals("RECEIVED_UNCORRELATED", second.getString("ack2")); assertEquals("UNKNOWN", second.getString("pixels"))
        set("variant", ClusterThemeVariant.CORNER_MARKER); set("mutated", true); set("state", "AWAITING_OBSERVATION")
        ClusterThemeLiveTest.observe(1, name, ClusterThemeVariant.MARKER); drain()
        assertFalse(field("markerObserved").getBoolean(null))
    }
    @Test fun `queued previous restore cannot clean or cancel a newer session`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        worker.execute { entered.countDown(); release.await() }; assertTrue(entered.await(1, TimeUnit.SECONDS))
        ClusterThemeLiveTest.requestRestore(name)
        count("generation").incrementAndGet(); val newer = name.replace("aaaa", "bbbb")
        set("filename", newer); set("state", "PREFLIGHT"); set("deadline", 123456L)
        flag("restoreRequested").set(false)
        release.countDown(); drain()
        val result = JSONObject(ClusterThemeLiveTest.status())
        assertEquals("PREFLIGHT", result.getString("state")); assertEquals(newer, result.getString("filename"))
        assertTrue(flag("active").get()); assertFalse(flag("restoreRequested").get())
        assertEquals(123456L, field("deadline").getLong(null))
    }
    @Test fun `corrupt journal is never proof of no previous mutation`() {
        set("journalTrusted", false); set("state", "QUARANTINE"); set("uncertain", true)
        ClusterThemeLiveTest.requestRestore(name); drain()
        assertTrue(flag("active").get())
        assertEquals("QUARANTINE", JSONObject(ClusterThemeLiveTest.status()).getString("state"))
    }
    @Test fun `late wrong nonce observations do nothing and duplicate callbacks are bounded`() {
        set("mutated", true); set("state", "AWAITING_OBSERVATION")
        ClusterThemeLiveTest.observe(1, name.replace("aaaa", "bbbb")); drain()
        assertFalse(field("markerObserved").getBoolean(null))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        worker.execute { entered.countDown(); release.await() }; assertTrue(entered.await(1, TimeUnit.SECONDS))
        repeat(100) { ClusterThemeLiveTest.observe(1, name) }
        assertEquals(1, (field("observationPending").get(null) as AtomicInteger).get())
        set("filename", name.replace("aaaa", "cccc")); count("generation").incrementAndGet()
        release.countDown(); drain()
        assertFalse(field("markerObserved").getBoolean(null))
    }
    @Test fun `carrier removal and default query do not release session without visual OEM observation`() {
        assertTrue(ClusterThemePolicy.canDeleteCarrier(defaultQueried = true, loadSettled = true, uncertain = false))
        set("state", "DEFAULT_QUERIED"); set("carrierRemoved", true); set("defaultQueried", true)
        set("loadSettled", true); set("oemObserved", false)
        ClusterThemeLiveTest::class.java.getDeclaredMethod("maybeCleanup").apply { isAccessible = true }.invoke(ClusterThemeLiveTest)
        assertEquals("DEFAULT_QUERIED", JSONObject(ClusterThemeLiveTest.status()).getString("state"))
        assertTrue(flag("active").get())
        assertFalse(field("oemObserved").getBoolean(null))
    }

    @Test fun `OEM observation received before default query cannot become a later confirmation`() {
        set("mutated", true); set("defaultQueried", false)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        worker.execute { entered.countDown(); release.await() }; assertTrue(entered.await(1, TimeUnit.SECONDS))
        ClusterThemeLiveTest.observe(2, name)
        set("defaultQueried", true)
        release.countDown(); drain()
        assertFalse(field("oemObserved").getBoolean(null))
    }

    @Test fun `marker observation cannot settle full surface load and theme observation stays distinct`() {
        set("mutated", true); set("loadSettled", false); set("variant", ClusterThemeVariant.FULL_SURFACE_TEST); set("state", "AWAITING_OBSERVATION")
        ClusterThemeLiveTest.observe(1, name); drain()
        assertFalse(field("markerObserved").getBoolean(null)); assertFalse(field("themeObserved").getBoolean(null))
        assertFalse(field("loadSettled").getBoolean(null))
        ClusterThemeLiveTest.observe(1, name, ClusterThemeVariant.FULL_SURFACE_TEST); drain()
        assertFalse(field("markerObserved").getBoolean(null)); assertTrue(field("themeObserved").getBoolean(null))
        assertTrue(field("loadSettled").getBoolean(null))
    }

    @Test fun `renderer discovery refusal cannot pin a renderer stage a carrier or start a helper`() {
        set("identity", ""); set("helperPid", 0); set("helperToken", "")
        val result = ClusterRendererDiscovery.scan(entries = { it("42") }, access = rendererInputStreams {
            throw java.nio.file.AccessDeniedException("private")
        })
        val method = ClusterThemeLiveTest::class.java.getDeclaredMethod("identifyRenderer", ClusterRendererDiscovery.Result::class.java)
            .apply { isAccessible = true }
        val error = runCatching { method.invoke(ClusterThemeLiveTest, result) }.exceptionOrNull() as InvocationTargetException
        assertTrue(error.cause is IllegalStateException)
        assertTrue(error.cause!!.message!!.contains("denied=1"))
        assertEquals("renderer-discovery", field("phase").get(null))
        assertEquals("", field("renderer").get(null)); assertEquals("", field("identity").get(null)); assertEquals("", field("root").get(null))
        assertFalse(field("mutated").getBoolean(null)); assertEquals(-1L, field("createdInode").getLong(null))
        assertEquals(0, field("helperPid").getInt(null)); assertEquals("", field("helperToken").get(null))
    }

    @Test fun `native errno remains distinguishable without exposing exception payloads`() {
        val method = ClusterThemeLiveTest::class.java.getDeclaredMethod("procFailure", Exception::class.java)
            .apply { isAccessible = true }
        for ((errno, kind, label) in listOf(
            Triple(OsConstants.EACCES, ClusterRendererDiscovery.FailureKind.DENIED, "EACCES"),
            Triple(OsConstants.EPERM, ClusterRendererDiscovery.FailureKind.DENIED, "EPERM"),
            Triple(OsConstants.ENOENT, ClusterRendererDiscovery.FailureKind.VANISHED, "ENOENT"),
            Triple(OsConstants.ESRCH, ClusterRendererDiscovery.FailureKind.VANISHED, "ESRCH"),
            Triple(OsConstants.EIO, ClusterRendererDiscovery.FailureKind.IO, "errno_${OsConstants.EIO}"),
        )) {
            val error = java.io.IOException("PRIVATE", ErrnoException("PRIVATE", errno))
            val result = method.invoke(ClusterThemeLiveTest, error) as ClusterRendererDiscovery.Failure
            assertEquals(kind, result.kind); assertEquals(label, result.label)
        }
    }

    @Test fun `read only query preserves the complete live test and recovery state`() {
        flag("active").set(false); set("state", "REFUSED"); set("detail", "previous renderer discovery refusal")
        set("mutated", false); set("loadSettled", true); set("defaultQueried", true); set("uncertain", false)
        val before = JSONObject(ClusterThemeLiveTest.status())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        ClusterThemeLiveTest.querySelection {
            entered.countDown(); release.await()
            ClusterThemeQueryRunner.Result(JSONObject().put("state", "RECEIVED").put("nativeIdentity", "UNKNOWN"), true)
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertEquals("PENDING", JSONObject(ClusterThemeLiveTest.status()).getJSONObject("oemQuery").getString("state"))
        assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.querySelection { error("must not run") } }
        release.countDown(); drain()
        val after = JSONObject(ClusterThemeLiveTest.status())
        for (key in before.keys()) if (key != "oemQuery" && key != "remainingMs" && key != "nativeMode")
            assertEquals(key, before.get(key).toString(), after.get(key).toString())
        assertEquals(before.getJSONObject("nativeMode").toString(), after.getJSONObject("nativeMode").toString())
        assertFalse(flag("active").get()); assertFalse(flag("restoreRequested").get()); assertFalse(flag("queryActive").get())
        assertTrue(field("loadSettled").getBoolean(null)); assertTrue(field("defaultQueried").getBoolean(null))
    }

    @Test fun `active recovery quarantine and an unreaped query cannot admit another query`() {
        assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.querySelection { error("must not run") } }
        flag("active").set(false); set("journalTrusted", false)
        assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.querySelection { error("must not run") } }
        set("journalTrusted", true)
        ClusterThemeLiveTest.querySelection { ClusterThemeQueryRunner.Result(JSONObject().put("state", "UNAVAILABLE"), false) }
        drain()
        assertTrue(flag("queryActive").get())
        assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.querySelection { error("must not run") } }
        assertFalse(field("mutated").getBoolean(null)); assertFalse(flag("restoreRequested").get())
    }

    @Test fun `missing ACK permits only fresh fault-free visual settlement before restore`() {
        for ((phase, uncertain) in listOf("QUARANTINE" to true, "RESTORING" to false, "AWAITING_OBSERVATION" to true)) {
            set("mutated", true); set("state", phase); set("uncertain", uncertain); set("loadSettled", false)
            ClusterThemeLiveTest.observe(1, name); drain()
            assertFalse(field("loadSettled").getBoolean(null)); assertFalse(field("markerObserved").getBoolean(null))
        }
        set("state", "AWAITING_OBSERVATION"); set("uncertain", false); flag("restoreRequested").set(true)
        ClusterThemeLiveTest.observe(1, name); drain(); assertFalse(field("loadSettled").getBoolean(null))
        flag("restoreRequested").set(false); ClusterThemeLiveTest.observe(1, name); drain()
        assertTrue(field("loadSettled").getBoolean(null)); assertTrue(field("markerObserved").getBoolean(null))
    }

    @Test fun `recovered helper access errors never prove the child absent`() {
        set("helperStart", "123")
        val denied = ErrnoException("lstat", OsConstants.EACCES)
        assertThrows(ErrnoException::class.java) {
            ClusterThemeLiveTest.recoveredGenerationPresent({ throw denied }, { error("No read permitted") })
        }
        assertThrows(java.io.IOException::class.java) {
            ClusterThemeLiveTest.recoveredGenerationPresent({}, { throw java.io.IOException("unreadable proc stat") })
        }
        assertTrue(ClusterThemeLiveTest.recoveredGenerationPresent({}, { "123" }))
        assertFalse(ClusterThemeLiveTest.recoveredGenerationPresent({}, { "456" }))
    }
    @Test fun `only explicit ENOENT proves a missing recovered helper including read races`() {
        set("helperStart", "123")
        assertFalse(ClusterThemeLiveTest.recoveredGenerationPresent({ throw ErrnoException("lstat", OsConstants.ENOENT) }, { error("Gone") }))
        var probes = 0
        assertFalse(ClusterThemeLiveTest.recoveredGenerationPresent({ if (++probes == 2) throw ErrnoException("lstat", OsConstants.ENOENT) },
            { throw java.io.IOException("raced exit") }))
        assertEquals(2, probes)
    }

    @Test fun `native admission refuses a production1000 call already admitted without waiting for its IPC`() {
        flag("active").set(false)
        ClusterThemeLiveTest.beginProductionModeCommand()
        try {
            val result = JSONObject(ClusterThemeLiveTest.nativeModeRequest(true, "d".repeat(32), true, true, android.os.Binder()))
            assertEquals("d".repeat(32), result.getString("nativeRejectedNonce"))
            assertTrue(result.getString("nativeRefusal").contains("Production mode command in flight"))
            assertFalse(result.getJSONObject("nativeMode").getBoolean("debt"))
        } finally { ClusterThemeLiveTest.endProductionModeCommand() }
    }

}
