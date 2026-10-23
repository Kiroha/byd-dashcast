package com.byd.dashcast.clusterlive

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterThemeDiagnosticsTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(ClusterThemeDiagnostics.PREFERENCES, Context.MODE_PRIVATE)
    private fun forgetMemory() {
        ClusterThemeDiagnostics::class.java.getDeclaredField("store").apply { isAccessible = true }.set(null, null)
    }
    @Before fun clear() { prefs.edit().clear().commit(); forgetMemory() }
    private fun status(state: String = "REFUSED", detail: String = "No renderer admitted", raw: String = "") =
        JSONObject().put("state", state).put("detail", detail).put("filename", "")
            .put("daemonUid", 2000).put("identity", "renderer-pid-start-mount")
            .put("mutated", false).put("uncertain", false).put("raw", raw)
    private fun savedEvents() = JSONObject(prefs.getString(ClusterThemeDiagnostics.KEY, "")!!).getJSONArray("events")

    @Test fun `empty raw early refusal survives app memory loss and records renderer identity honestly`() {
        ClusterThemeDiagnostics.received(app, "start", "app-local-binder", status().toString())
        forgetMemory()
        val report = ClusterThemeDiagnostics.snapshot(app)
        assertTrue(report.contains("REFUSED")); assertTrue(report.contains("No renderer admitted"))
        assertTrue(report.contains("rendererIdentity")); assertTrue(report.contains("NOT daemon-process identity"))
        assertTrue(report.contains("current physical cluster state=UNKNOWN")); assertTrue(report.contains("receivedEpochMs"))
        assertFalse(report.contains("\"raw\""))
    }
    @Test fun `read only query receipt keeps separate native results local time and unknown renderer identity`() {
        val query = JSONObject().put("state", "RECEIVED").put("phase", "complete").put("detail", "selection observation only")
            .put("registration", 0).put("native", -1).put("selection", "default").put("nativeIdentity", "UNKNOWN")
            .put("receivedEpochMs", 123456789L)
        ClusterThemeDiagnostics.received(app, "oem-selection-query", "connection1", status().put("oemQuery", query).toString())
        forgetMemory()
        val report = ClusterThemeDiagnostics.snapshot(app)
        assertTrue(report.contains("oemQueryRegistration")); assertTrue(report.contains("oemQueryNative\":-1"))
        assertTrue(report.contains("oemQueryReceivedEpochMs\":123456789"))
        assertTrue(report.contains("oemQueryNativeIdentity\":\"UNKNOWN"))
        assertTrue(report.contains("selection observation only")); assertTrue(report.contains("REFUSED"))
    }
    @Test fun `decoded OEM negative code and read after failure survive historical export without raw payloads`() {
        val negative = ClusterThemeProtocol.Response(1005, 0, listOf(3, 0, 0x2330, 0, 0), List(5) { "" })
        ClusterThemeDiagnostics.received(app, "android-ivi-restore", "connection1", status("QUARANTINE",
            "Default recovery is unconfirmed. ${negative.resourceFailureSummary()}", "OPAQUE BASE64 PAYLOAD")
            .put("trace", "resource-response ${negative.resourceFailureSummary()}\nselection-received path=default")
            .put("transport", "android-ivi").put("rccDebt", true).put("journalSchema", "4").put("carrierIdentity", "device=39 inode=27308")
            .put("retirementAvailable", false).put("retirement", ClusterThemeRetirement(".dashcast-retired-" + "b".repeat(32), 39, 99, true).json()).toString())
        forgetMemory()
        val report = ClusterThemeDiagnostics.snapshot(app)
        assertTrue(report.contains("RESOURCE_IS_SAME")); assertTrue(report.contains("0x2330"))
        assertTrue(report.contains("9008")); assertTrue(report.contains("selection-received path=default"))
        assertTrue(report.contains("rccDebt\":true")); assertFalse(report.contains("OPAQUE BASE64 PAYLOAD"))
        assertTrue(report.contains("journalSchema\":\"4")); assertTrue(report.contains("device=39 inode=27308"))
        assertTrue(report.contains(".dashcast-retired-")); assertTrue(report.contains("retirementAvailable\":false"))
    }

    @Test fun `unchanged polls ignore countdown and operation but changed detail state or raw are retained`() {
        val first = status().put("remainingMs", 30000)
        ClusterThemeDiagnostics.received(app, "start", "connection1", first.toString())
        repeat(20) {
            ClusterThemeDiagnostics.received(app, "status", "connection1", first.put("remainingMs", it).toString())
        }
        assertEquals(1, savedEvents().length())
        ClusterThemeDiagnostics.received(app, "status", "connection1", status(detail = "Different refusal").toString())
        ClusterThemeDiagnostics.received(app, "status", "connection1", status(state = "QUARANTINE").toString())
        ClusterThemeDiagnostics.received(app, "status", "connection1", status(raw = "secretParcelBase64").toString())
        val events = savedEvents()
        assertEquals(4, events.length())
        assertTrue(events.getJSONObject(3).has("rawSha256"))
        assertFalse(ClusterThemeDiagnostics.snapshot(app).contains("secretParcelBase64"))
    }
    @Test fun `queued recovery and terminal failure remain distinct in exported history without clearing debt`() {
        val pending = status("QUARANTINE", "Recovered previous session").put("rccDebt", true).put("recoveryPending", true)
        ClusterThemeDiagnostics.received(app, "android-ivi-restore", "connection1", pending.toString())
        ClusterThemeDiagnostics.received(app, "status", "connection1", JSONObject(pending.toString()).put("recoveryPending", false)
            .put("detail", "Helper readiness unavailable before admission; no GO sent").toString())
        forgetMemory()
        val report = ClusterThemeDiagnostics.snapshot(app)
        assertTrue(report.contains("recoveryPending\":true")); assertTrue(report.contains("recoveryPending\":false"))
        assertTrue(report.contains("rccDebt\":true")); assertTrue(report.contains("Helper readiness unavailable"))
        assertEquals(2, savedEvents().length())
    }

    @Test fun `local failures do not hide or refresh previous status and remain historical after restart`() {
        ClusterThemeDiagnostics.received(app, "status", "connection1", status(state = "CLEAN").toString())
        val receipt = savedEvents().getJSONObject(0).getLong("receivedEpochMs")
        ClusterThemeDiagnostics.local(app, "status-failed", "LOCAL", "Pinned daemon unavailable", "No reconnect")
        ClusterThemeDiagnostics.received(app, "status", "connection1", status(state = "CLEAN").toString())
        assertEquals(2, savedEvents().length())
        assertEquals(receipt, savedEvents().getJSONObject(0).getLong("receivedEpochMs"))
        forgetMemory()
        val report = ClusterThemeDiagnostics.snapshot(app, receipt + 86_400_000)
        assertTrue(report.contains("ageMs=86400000 historical"))
        assertTrue(report.contains("Pinned daemon unavailable"))
        assertTrue(report.contains("current physical cluster state=UNKNOWN"))
    }

    @Test fun `missing cache says not recorded rather than idle or successful`() {
        val report = ClusterThemeDiagnostics.snapshot(app)
        assertTrue(report.contains("outcome=NOT_RECORDED")); assertTrue(report.contains("UNKNOWN"))
    }

    @Test fun `corrupt oversized and wrong typed cache remain unknown`() {
        for (text in listOf("not json", "x".repeat(ClusterThemeDiagnostics.MAX_BYTES + 1),
            """{"schema":1,"events":[{"kind":"STATUS","operation":"status","state":"CLEAN","receivedEpochMs":"wrong"}]}""",
            """{"schema":1,"events":[{"kind":"STATUS","operation":"status","state":"CLEAN","receivedEpochMs":0.5}]}""",
            """{"schema":1,"events":[{"kind":"STATUS","operation":"status","state":"CLEAN","receivedEpochMs":1,"daemonUid":0.5}]}""")) {
            prefs.edit().putString(ClusterThemeDiagnostics.KEY, text).commit(); forgetMemory()
            val report = ClusterThemeDiagnostics.snapshot(app)
            assertTrue(report.contains("cacheIssue=")); assertTrue(report.contains("outcome=NOT_RECORDED"))
            assertFalse(report.contains("\"state\":\"CLEAN\""))
        }
    }

    @Test fun `cache and scalar fields remain bounded for unicode payload floods`() {
        repeat(100) {
            ClusterThemeDiagnostics.local(app, "failure-$it", "LOCAL", "é".repeat(10000), "€".repeat(10000))
        }
        val serialized = prefs.getString(ClusterThemeDiagnostics.KEY, "")!!
        assertTrue(serialized.toByteArray(Charsets.UTF_8).size <= ClusterThemeDiagnostics.MAX_BYTES)
        assertTrue(savedEvents().length() <= 24)
        assertTrue(savedEvents().getJSONObject(savedEvents().length() - 1).getString("error").length <= 384)
    }

    @Test fun `clock reversal is explicit unknown rather than fresh evidence`() {
        ClusterThemeDiagnostics.received(app, "status", "connection1", status().toString())
        assertTrue(ClusterThemeDiagnostics.snapshot(app, 1).contains("ageMs=UNKNOWN(clock changed)"))
    }

    @Test fun `explicit command receipts survive identical state while distinct delimiter text cannot collide`() {
        ClusterThemeDiagnostics.received(app, "status", "connection1", status().toString())
        ClusterThemeDiagnostics.received(app, "restore", "connection1", status().toString())
        assertEquals(2, savedEvents().length())
        ClusterThemeDiagnostics.local(app, "failure", "LOCAL", "a|error=b", "c")
        ClusterThemeDiagnostics.local(app, "failure", "LOCAL", "a", "b|error=c")
        assertEquals(4, savedEvents().length())
    }

    @Test fun `alternating session and UI poll failures retain original refusal without cache churn`() {
        ClusterThemeDiagnostics.received(app, "start", "connection1", status().toString())
        repeat(100) {
            ClusterThemeDiagnostics.local(app, "status", "UNKNOWN", "Pinned daemon lost", connectionToken = "connection1")
            ClusterThemeDiagnostics.local(app, "status-failed", "LOCAL", "REFUSED/UNKNOWN: Pinned daemon lost")
        }
        assertEquals(3, savedEvents().length())
        assertTrue(savedEvents().getJSONObject(0).getString("detail").contains("No renderer admitted"))
        ClusterThemeDiagnostics.local(app, "status", "UNKNOWN", "Pinned daemon lost", connectionToken = "connection2")
        assertEquals(4, savedEvents().length())
    }

    @Test fun `profile hash stage and separate theme observation survive historical cache reload`() {
        val profile = ClusterThemeVariant.FULL_SURFACE_TEST
        ClusterThemeDiagnostics.received(app, "start-full-surface", "connection1", status()
            .put("variant", profile.id).put("carrierSha256", profile.carrierSha256)
            .put("phase", "renderer-artifact").put("themeObservedByUser", false).toString())
        forgetMemory()
        val report = ClusterThemeDiagnostics.snapshot(app)
        assertTrue(report.contains(profile.id)); assertTrue(report.contains(profile.carrierSha256))
        assertTrue(report.contains("renderer-artifact")); assertTrue(report.contains("themeObservedByUser"))
    }

    @Test fun `renderer enumeration refusal and errno survive historical cache reload without raw output`() {
        val result = ClusterRendererDiscovery.scan(entries = { visit ->
            visit("1"); throw java.nio.file.AccessDeniedException("PRIVATE PATH")
        }, access = rendererInputStreams { throw java.io.IOException("PRIVATE ERROR") }, classify = {
            if (it is java.nio.file.AccessDeniedException)
                ClusterRendererDiscovery.Failure(ClusterRendererDiscovery.FailureKind.DENIED, "EACCES")
            else ClusterRendererDiscovery.Failure(ClusterRendererDiscovery.FailureKind.IO, "errno_5")
        })
        val error = runCatching { result.requireSingle() }.exceptionOrNull()!!
        val detail = "renderer-discovery: ${error.javaClass.simpleName}: ${error.message}"
        assertTrue(detail.length <= 768)
        ClusterThemeDiagnostics.received(app, "start-full-surface", "connection1", status(detail = detail)
            .put("phase", "renderer-discovery").toString())
        forgetMemory()
        val report = ClusterThemeDiagnostics.snapshot(app)
        val restored = JSONObject(report.lineSequence().first { it.contains(" historical {") }.substringAfter(" historical "))
        assertEquals(detail, restored.getString("detail")); assertTrue(report.contains("list=DENIED:EACCES"))
        assertTrue(report.contains("errors=1")); assertTrue(report.contains("first=cmdline:errno_5"))
        assertTrue(report.contains("Contexte cluster")); assertTrue(report.contains("unavailable is not absent"))
        assertFalse(report.contains("PRIVATE")); assertFalse(report.contains("rawSha256"))
    }
}
