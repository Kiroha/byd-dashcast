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
}
