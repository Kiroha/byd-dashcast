package com.byd.dashcast.ui.diag

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterContextExportTest {
    @get:Rule val temporary = TemporaryFolder()

    private open class Fake(val payload: ByteArray = byteArrayOf(0, -1, 42, 0)) : OemBootstrapExport.Device {
        val calls = mutableListOf<String>()
        val pathsRead = mutableListOf<String>()
        var changeAfter = false
        var mismatchShaAfter = false
        var denied = false
        var truncatedMaps = false
        var reusedPid = false
        var declaredSize = payload.size.toLong()
        var fatal = false
        override val terminated: Boolean get() = fatal
        override fun negotiate(check: () -> Unit): Map<String, String> {
            calls += "negotiate"; check()
            return mapOf("daemon_uid" to "2000", "daemon_pid" to "99", "daemon_protocol" to "26",
                "daemon_instance_nonce" to "0123456789abcdef0123456789abcdef")
        }
        override fun metadata(label: String, pid: Int?): List<OemBootstrapPolicy.Blob> {
            calls += label
            val (_, limits) = ClusterContextPolicy.script(label, pid)
            return limits.map { (id, cap) ->
                var status = "ok"
                val bytes = when {
                    id == "ps" -> "PID NAME\n11 qtandroidnative\n12 com.xdja.containerservice\n13 surfaceflinger\n".toByteArray()
                    id.startsWith("prop_") -> "fixture_${id}\n".toByteArray()
                    label.startsWith("sources_") -> {
                        val i = id.substringAfterLast('_').toInt()
                        if (denied && i == 0) { status = "access_denied"; "Permission denied\n".toByteArray() }
                        else when {
                            id.startsWith("stat_") -> "81a4:${if (i == 0) declaredSize else 42}:${if (changeAfter && label == "sources_after" && i == 0) 2 else 1}:1:${i + 100}:1\n".toByteArray()
                            id.startsWith("canonical_") -> "${ClusterContextPolicy.paths[i]}\n".toByteArray()
                            else -> "${if (mismatchShaAfter && label == "sources_after" && i == 0) "0".repeat(64) else hash(if (i == 0) payload else byteArrayOf())}  ${ClusterContextPolicy.paths[i]}\n".toByteArray()
                        }
                    }
                    id == "stat_before" || id == "stat_after" -> OemBootstrapPolicyTest.statBytes(pid!!,
                        name(pid).take(15), if (reusedPid && id == "stat_after") "78" else "77")
                    id == "cmdline_argv0" -> "${if (pid == 12) name(pid) else "/system/bin/${name(pid!!)}"}\u0000".toByteArray()
                    id == "maps" && truncatedMaps -> { status = "truncated"; ByteArray(cap) { 65 } }
                    else -> "metadata\n".toByteArray()
                }
                OemBootstrapPolicy.Blob(id, cap, status, 0, bytes)
            }
        }
        private fun name(pid: Int) = when (pid) { 11 -> "qtandroidnative"; 12 -> "com.xdja.containerservice"; else -> "surfaceflinger" }
        override fun chunk(entry: OemBootstrapPolicy.Source, offset: Long, length: Int): ByteArray {
            calls += "chunk"
            pathsRead += entry.path
            return payload.copyOfRange(offset.toInt(), minOf(payload.size, offset.toInt() + length))
        }
    }

    private fun run(fake: OemBootstrapExport.Device, dir: File, stop: () -> Boolean = { false },
                    clock: () -> Long = FullApkExport::monotonicMs) =
        ClusterContextExport(fake, dir, stop, {}, { Long.MAX_VALUE }, clock).run()
    private fun report(dir: File) = JSONObject(File(dir, "manifest.json").readText())
    private fun status(dir: File) = report(dir).getJSONArray("files").getJSONObject(0).getString("status")

    @Test fun `exact provider bytes require matching pre post stat and SHA and other paths are metadata only`() {
        val fake = Fake(ByteArray(OemBootstrapPolicy.CHUNK + 3) { (it % 256).toByte() })
        val dir = temporary.newFolder()
        val result = run(fake, dir)
        assertEquals("finished", result.outcome)
        assertEquals("exported", status(dir))
        assertEquals(listOf(ClusterContextPolicy.PROVIDER, ClusterContextPolicy.PROVIDER), fake.pathsRead)
        val metadata = report(dir)
        assertEquals(3, metadata.getJSONArray("processes").length())
        assertTrue(metadata.getBoolean("properties_bracket_consistent"))
        assertEquals(fake.payload.size.toLong(), metadata.getLong("source_read_bytes"))
        assertTrue(metadata.getLong("metadata_bytes_charged") < OemBootstrapPolicy.MAX_METADATA_BYTES)
        ZipFile(result.archives.single()).use { zip ->
            assertArrayEquals(fake.payload, zip.getInputStream(zip.getEntry("cluster/libwindowsurface_android.so")).readBytes())
            assertEquals(5, zip.size())
            assertNull(zip.getEntry("cluster/libutils.so"))
        }
        assertEquals(hash(result.archives.single().readBytes()), JSONObject(File(dir, "integrite.json").readText()).getString("sha256"))
        assertFalse(File(dir, "libwindowsurface_android.so.part").exists())
    }

    @Test fun `changed identity or source hash never finalizes the copied provider`() {
        for (mode in listOf("stat", "sha")) {
            val fake = Fake().apply { changeAfter = mode == "stat"; mismatchShaAfter = mode == "sha" }
            val dir = temporary.newFolder()
            val result = run(fake, dir)
            assertEquals("partial", result.outcome)
            assertEquals("source_changed_or_postcheck_unverified", status(dir))
            assertFalse(File(dir, "libwindowsurface_android.so").exists())
            ZipFile(result.archives.single()).use { assertEquals(4, it.size()) }
        }
    }

    @Test fun `denial and oversize remain explicit without any strict source read`() {
        for (mode in listOf("denied", "oversize")) {
            val fake = Fake().apply { denied = mode == "denied"; if (mode == "oversize") declaredSize = OemBootstrapPolicy.MAX_FILE_BYTES + 1 }
            val dir = temporary.newFolder()
            val result = run(fake, dir)
            assertEquals("partial", result.outcome)
            assertTrue(fake.pathsRead.isEmpty())
            assertEquals(if (mode == "denied") "access_denied" else "file_byte_limit", status(dir))
            assertEquals(1, result.archives.size)
        }
    }

    @Test fun `truncated maps and PID reuse remain partial while verified file stays useful`() {
        val fake = Fake().apply { truncatedMaps = true; reusedPid = true }
        val dir = temporary.newFolder()
        val result = run(fake, dir)
        assertEquals("partial", result.outcome)
        assertEquals("exported", status(dir))
        val processes = report(dir).getJSONArray("processes")
        for (i in 0 until processes.length()) {
            val pid = processes.getJSONObject(i)
            assertEquals("unstable_or_unobserved", pid.getString("identity_status"))
            assertEquals(65536, pid.getInt("maps_byte_limit"))
        }
    }

    @Test fun `cancellation after a returned chunk rejects current bytes and skips further calls`() {
        val stop = AtomicBoolean(false)
        val fake = object : Fake() {
            override fun chunk(entry: OemBootstrapPolicy.Source, offset: Long, length: Int): ByteArray {
                val bytes = super.chunk(entry, offset, length); stop.set(true); return bytes
            }
        }
        val dir = temporary.newFolder()
        val result = run(fake, dir, stop::get)
        assertEquals("cancelled", result.outcome)
        assertEquals("cancelled_incomplete", status(dir))
        assertEquals(listOf("negotiate", "inventory_before", "sources_before", "chunk"), fake.calls)
        assertFalse(File(dir, "libwindowsurface_android.so.part").exists())
        ZipFile(result.archives.single()).use { assertEquals(4, it.size()) }
    }

    @Test fun `terminal physical read stops subsequent processes and postcheck without retry`() {
        val fake = object : Fake() {
            override fun chunk(entry: OemBootstrapPolicy.Source, offset: Long, length: Int): ByteArray {
                super.chunk(entry, offset, length); fatal = true; throw IOException("timeout; physical call quarantined")
            }
        }
        val dir = temporary.newFolder()
        assertEquals("partial", run(fake, dir).outcome)
        assertEquals(listOf("negotiate", "inventory_before", "sources_before", "chunk"), fake.calls)
        assertEquals("read_failed", status(dir))
    }

    @Test fun `soft acquisition budget stops before next operation while local partial ZIP can finalize`() {
        var now = 0L
        val fake = object : Fake() {
            override fun metadata(label: String, pid: Int?): List<OemBootstrapPolicy.Blob> {
                val result = super.metadata(label, pid)
                if (label == "inventory_before") now = ClusterContextPolicy.COLLECT_MS
                return result
            }
        }
        val dir = temporary.newFolder()
        val result = run(fake, dir, clock = { now })
        assertEquals("partial", result.outcome)
        assertEquals(listOf("negotiate", "inventory_before"), fake.calls)
        assertEquals(1, result.archives.size)
    }

    @Test fun `cluster preset appears only in OEM submenu and explains passive collection before launch`() {
        val controller = Robolectric.buildActivity(DiagActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            fun buttons(view: View): List<Button> = when (view) {
                is Button -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
                else -> emptyList()
            }
            val all = buttons(activity.window.decorView)
            assertEquals(1, all.count { it.text.toString() == "Relevés locaux OEM" })
            assertFalse(all.any { "Contexte cluster" in it.text.toString() })
            all.single { it.text.toString() == "Relevés locaux OEM" }.performClick()
            val menu = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("Contexte cluster — bibliothèque et processus", menu.listView.adapter.getItem(4))
            menu.listView.performItemClick(menu.listView.getChildAt(4), 4, 4)
            val confirm = ShadowAlertDialog.getLatestAlertDialog()
            val message = confirm.findViewById<android.widget.TextView>(android.R.id.message).text.toString()
            assertTrue(message.contains("aucun affichage activé", ignoreCase = true))
            assertTrue(message.contains("3 min"))
            confirm.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `previous cluster report keeps profile recovery and bounded OEM history`() {
        val base = temporary.newFolder()
        val dir = File(base, "${ExportKind.CLUSTER_CONTEXT.prefix}20261018-235900-000").apply { mkdir() }
        File(dir, "cluster-context-001.zip").writeBytes(byteArrayOf())
        val history = FullApkExportSession.previousOemReports(listOf(ExportKind.CLUSTER_CONTEXT to base))
        assertEquals(listOf(ExportKind.CLUSTER_CONTEXT to dir), history.entries)
        val result = FullApkExportSession.recoverPrevious(ExportKind.CLUSTER_CONTEXT, dir)
        assertEquals(ExportKind.CLUSTER_CONTEXT, result.kind)
        assertFalse(result.countsKnown)
        assertEquals(1, result.archives.size)
    }

    companion object {
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
