package com.byd.dashcast.clusterlive

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterThemeJournalTest {
    @Test fun `restart intent and completed observation remain strictly correlated across recovery`() {
        val before = "11111111-1111-1111-1111-111111111111"
        val after = "22222222-2222-2222-2222-222222222222"
        val j = journal().put("schema", 4).put("mutated", true).put("createdDevice", 1).put("createdInode", 9)
            .put("retirement", ClusterThemeRetirement(".dashcast-retired-" + "b".repeat(32), 1, 15, true).json())
            .put("restartDispatchPending", true).put("restartBootId", before).put("restartRound", "c".repeat(32))
        assertTrue(ClusterThemeJournal.recover(j).restartDispatchPending)
        for ((key, value) in listOf("restartDispatchPending" to "true", "restartBootId" to "", "restartRound" to "foreign", "state" to "CLEAN"))
            assertThrows(Exception::class.java) { ClusterThemeJournal.recover(JSONObject(j.toString()).put(key, value)) }
        val completed = ClusterRestartCompletion("c".repeat(32), before, after)
        val clean = JSONObject(j.toString()).put("state", "CLEAN").put("iviRecoverySettled", true)
            .put("restartDispatchPending", false).put("restartCompleted", completed.json())
        assertEquals(completed, ClusterThemeJournal.recover(clean).restartCompleted)
        assertThrows(Exception::class.java) { ClusterThemeJournal.recover(JSONObject(clean.toString()).put("state", "QUARANTINE")) }
        for ((key, value) in listOf("round" to 123, "beforeBoot" to after, "confirmedBoot" to "unknown"))
            assertThrows(Exception::class.java) { ClusterThemeJournal.recover(JSONObject(clean.toString())
                .put("restartCompleted", JSONObject(completed.json().toString()).put(key, value))) }
    }
    @Test fun `full app ADAS recovery is durable and cannot coexist with a clean journal`() {
        val variant = ClusterThemeVariant.FULL_APP_21F7735C
        val dashboard = JSONObject().put("stage", "CLOSED_RECEIVED").put("nativeDebt", false)
            .put("helperCommand", -1).put("helperPid", 0).put("helperStart", "").put("helperToken", "")
        val state = journal().put("variant", variant.id).put("carrierSha256", variant.carrierSha256)
            .put("dashboard", dashboard).put("fullAppAdas", JSONObject().put("debt", true).put("round", "b".repeat(32)))
        val recovered = ClusterThemeJournal.recover(state)
        assertTrue(recovered.fullAppAdasDebt); assertEquals("b".repeat(32), recovered.fullAppAdasRound)
        assertThrows(Exception::class.java) { ClusterThemeJournal.recover(JSONObject(state.toString()).put("state", "CLEAN")) }
        assertThrows(Exception::class.java) { ClusterThemeJournal.recover(JSONObject(state.toString()).put("fullAppAdas", JSONObject().put("debt", true).put("round", "foreign"))) }
        state.getJSONObject("fullAppAdas").put("debt", false)
        state.put("factoryApplyPending", true)
        assertTrue(ClusterThemeJournal.recover(state).factoryApplyPending)
        assertThrows(Exception::class.java) { ClusterThemeJournal.recover(JSONObject(state.toString()).put("state", "CLEAN")) }
    }
    private fun journal(schema: Int = 3) = JSONObject().put("schema", schema).put("state", "STAGING")
        .put("filename", ClusterThemePolicy.PREFIX + "a".repeat(32) + ".rcc")
        .put("mutated", false).put("helperPid", 0).put("helperStart", "").put("helperToken", "")
        .apply {
            if (schema >= 2) put("variant", "marker").put("carrierSha256", ClusterThemeVariant.MARKER.carrierSha256)
            if (schema == 3) put("transport", "android-ivi").put("root", "/storage/emulated/0/.cluster/translate/").put("identity", "1:2/1:3/1:4")
        }
    @Test fun `both legacy schemas retain strict identity while IVI recovery stays explicitly separate`() {
        for (schema in listOf(1, 2)) {
            val recovered = ClusterThemeJournal.recover(journal(schema))
            assertEquals(ClusterThemeTransport.STRICT_RENDERER, recovered.transport); assertFalse(recovered.clean)
        }
        val recovered = ClusterThemeJournal.recover(journal())
        assertEquals(ClusterThemeTransport.ANDROID_IVI, recovered.transport); assertFalse(recovered.clean)
    }
    @Test fun `new corner profile cannot select a legacy strict renderer recovery transport`() {
        for (profile in listOf(ClusterThemeVariant.CORNER_MARKER, ClusterThemeVariant.CORNER_MARKER_21F7735C)) {
            for (schema in listOf(1, 2)) assertThrows(Exception::class.java) {
                ClusterThemeJournal.recover(journal(schema).put("variant", profile.id).put("carrierSha256", profile.carrierSha256))
            }
            assertEquals(profile, ClusterThemeJournal.recover(journal().put("variant", profile.id).put("carrierSha256", profile.carrierSha256)).variant)
        }
    }
    @Test fun `altered profile transport hash path or nonzero user journal cannot authorize recovery`() {
        for ((key, value) in listOf("transport" to "strict-renderer", "carrierSha256" to "0".repeat(64),
            "root" to "/storage/emulated/10/.cluster/translate/", "identity" to "unknown", "filename" to "user.rcc")) {
            assertThrows(Exception::class.java) { ClusterThemeJournal.recover(journal().put(key, value)) }
        }
    }
    @Test fun `clean journal must have no helper and integers booleans are not coerced`() {
        val clean = journal().put("state", "CLEAN")
        assertTrue(ClusterThemeJournal.recover(clean).clean)
        for ((key, value) in listOf("helperPid" to -1, "helperPid" to "0", "mutated" to "false", "helperToken" to "a".repeat(32)))
            assertThrows(Exception::class.java) { ClusterThemeJournal.recover(JSONObject(clean.toString()).put(key, value)) }
    }
    @Test fun `unadmitted helper token is valid only with zero pid and a positive helper has full identity`() {
        assertEquals(0, ClusterThemeJournal.recover(journal().put("helperToken", "a".repeat(32))).helperPid)
        val valid = journal().put("helperPid", 42).put("helperStart", "12345").put("helperToken", "a".repeat(32))
        assertEquals(42, ClusterThemeJournal.recover(valid).helperPid)
        for ((key, value) in listOf("helperStart" to "", "helperToken" to "", "helperPid" to 1.5))
            assertThrows(Exception::class.java) { ClusterThemeJournal.recover(JSONObject(valid.toString()).put(key, value)) }
    }
    @Test fun `queued pre-storage journal permits no directory but admitted mutation requires one`() {
        val queued = journal().put("state", "QUEUED").put("root", "").put("identity", "")
        assertFalse(ClusterThemeJournal.recover(queued).mutated)
        assertThrows(Exception::class.java) { ClusterThemeJournal.recover(queued.put("mutated", true)) }
        assertThrows(Exception::class.java) { ClusterThemeJournal.recover(journal().put("mutated", true)) }
        assertTrue(ClusterThemeJournal.recover(journal().put("mutated", true).put("createdDevice", 1).put("createdInode", 9)).mutated)
    }
    @Test fun `schema4 requires explicit retirement metadata and cannot leak into legacy profiles`() {
        val retirement = ClusterThemeRetirement(".dashcast-retired-" + "b".repeat(32), 1, 15, false)
        val j = journal().put("schema", 4).put("mutated", true).put("createdDevice", 1).put("createdInode", 9)
            .put("retirement", retirement.json())
        assertEquals(retirement, ClusterThemeJournal.recover(j).retirement)
        for (schema in listOf(1, 2, 3)) assertThrows(Exception::class.java) { ClusterThemeJournal.recover(JSONObject(j.toString()).put("schema", schema)) }
        for ((key, value) in listOf("directory" to "../foreign", "directory" to "user.rcc", "device" to 2,
            "inode" to 9, "inode" to "15", "moved" to "false")) {
            val bad = JSONObject(j.toString()).put("retirement", JSONObject(retirement.json().toString()).put(key, value))
            assertThrows(Exception::class.java) { ClusterThemeJournal.recover(bad) }
        }
        assertThrows(Exception::class.java) { ClusterThemeJournal.recover(JSONObject(j.toString()).put("iviRecoverySettled", true)) }
        val complete = JSONObject(j.toString()).put("retirement", retirement.copy(moved = true).json())
            .put("iviRecoverySettled", true).put("state", "CLEAN")
        assertTrue(ClusterThemeJournal.recover(complete).clean)
        assertThrows(Exception::class.java) { ClusterThemeJournal.recover(complete.put("iviRecoverySettled", false)) }
    }
    @Test fun `dashboard native helper and RCC helper retain separate validated identities across restart`() {
        val variant = ClusterThemeVariant.FULL_DASHBOARD_21F7735C
        val state = JSONObject().put("stage", "UNKNOWN").put("nativeDebt", true)
            .put("helperCommand", 18).put("helperPid", 43).put("helperStart", "9876").put("helperToken", "b".repeat(32))
        val j = journal().put("variant", variant.id).put("carrierSha256", variant.carrierSha256)
            .put("mutated", true).put("createdDevice", 1).put("createdInode", 9)
            .put("helperPid", 42).put("helperStart", "1234").put("helperToken", "a".repeat(32)).put("dashboard", state)
        val recovered = ClusterThemeJournal.recover(j)
        assertEquals(42, recovered.helperPid); assertEquals(43, recovered.dashboardHelperPid)
        assertEquals(18, recovered.dashboardHelperCommand); assertTrue(recovered.dashboardNativeDebt)
        for ((key, value) in listOf("helperPid" to -1, "helperPid" to "43", "helperPid" to 1.5,
            "helperCommand" to 1, "nativeDebt" to "true", "helperToken" to "foreign", "helperStart" to "")) {
            val bad = JSONObject(j.toString()).put("dashboard", JSONObject(state.toString()).put(key, value))
            assertThrows(Exception::class.java) { ClusterThemeJournal.recover(bad) }
        }
        assertThrows(Exception::class.java) { ClusterThemeJournal.recover(JSONObject(j.toString()).put("state", "CLEAN")) }
        val nativeClosed = JSONObject(state.toString()).put("stage", "CLOSED_RECEIVED").put("nativeDebt", false)
            .put("helperCommand", -1).put("helperPid", 0).put("helperStart", "").put("helperToken", "")
        val clean = JSONObject(j.toString()).put("state", "CLEAN").put("helperPid", 0).put("helperStart", "").put("helperToken", "").put("dashboard", nativeClosed)
        assertTrue(ClusterThemeJournal.recover(clean).clean)
    }
    @Test fun `bootstrap recovery before custom mutation is durable while legacy size admission stays unknown`() {
        val variant = ClusterThemeVariant.FULL_DASHBOARD_21F7735C
        val native = JSONObject().put("stage", "NONE").put("nativeDebt", true)
            .put("helperCommand", 16).put("helperPid", 43).put("helperStart", "9876").put("helperToken", "b".repeat(32))
        val j = journal().put("variant", variant.id).put("carrierSha256", variant.carrierSha256).put("dashboard", native)
        val old = ClusterThemeJournal.recover(j)
        assertNull(old.dashboardSizeAdmitted); assertFalse(old.dashboardBootstrapStarted)
        native.put("bootstrapStarted", true).put("sizeAdmitted", false).put("bootstrapRecoverySettled", false).put("smallPanelSeen", true)
        val boot = ClusterThemeJournal.recover(j)
        assertTrue(boot.dashboardBootstrapStarted); assertFalse(boot.mutated); assertEquals(false, boot.dashboardSizeAdmitted)
        assertTrue(boot.dashboardNativeDebt); assertTrue(boot.dashboardSmallPanelSeen)
        assertThrows(Exception::class.java) { ClusterThemeJournal.recover(JSONObject(j.toString()).put("state", "CLEAN")) }
        for ((key, value) in listOf("bootstrapStarted" to "true", "sizeAdmitted" to 1, "bootstrapRecoverySettled" to true, "sizeAdmitted" to true))
            assertThrows(Exception::class.java) { ClusterThemeJournal.recover(JSONObject(j.toString()).put("dashboard", JSONObject(native.toString()).put(key, value))) }
        native.put("stage", "CLOSED_RECEIVED").put("nativeDebt", false).put("helperCommand", -1)
            .put("helperPid", 0).put("helperStart", "").put("helperToken", "")
        assertThrows(Exception::class.java) { ClusterThemeJournal.recover(JSONObject(j.toString()).put("state", "CLEAN")) }
        native.put("bootstrapRecoverySettled", true)
        assertTrue(ClusterThemeJournal.recover(JSONObject(j.toString()).put("state", "CLEAN")).clean)
    }
}
