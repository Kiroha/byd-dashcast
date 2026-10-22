package com.byd.dashcast.ui.diag

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OemBootstrapExportTest {
    @get:Rule val temporary = TemporaryFolder()
    private open class Fake(val payloads: Map<Int, ByteArray> = mapOf(0 to byteArrayOf(0, -1, 1), 1 to byteArrayOf()),
                            val sizes: Map<Int, Long> = emptyMap()) : OemBootstrapExport.Device {
        var reads = 0; val metadataCalls = mutableListOf<String>(); var requested = 0L
        override fun negotiate(check: () -> Unit): Map<String, String> {
            check(); check(); return mapOf("daemon_uid" to "2000", "daemon_pid" to "99", "daemon_protocol" to "26")
        }
        override fun metadata(label: String, pid: Int?): List<OemBootstrapPolicy.Blob> {
            metadataCalls.add(label)
            val limits = when (label) { "inventory" -> OemBootstrapPolicy.inventoryLimits; else -> if (label.startsWith("sources_")) OemBootstrapPolicy.sourceLimits(label.removePrefix("sources_").toInt()) else OemBootstrapPolicy.processLimits; }
            return limits.map { (id, cap) ->
                val bytes: ByteArray; var status = "ok"
                when {
                    label == "inventory" -> bytes = (if (id == "ps") "PID NAME\n1 init\n99 app_process\n123 strategyservice\n" else "fixture\n").toByteArray()
                    label.startsWith("sources_") -> {
                        val index = id.substringAfterLast('_').toInt()
                        val size = sizes[index] ?: payloads[index]?.size?.toLong()
                        if (size == null) { bytes = "stat: Permission denied\n".toByteArray(); status = "access_denied" }
                        else bytes = (if (id.startsWith("stat_")) "81a4:$size:1:1:${index + 100}:1\n" else "${OemBootstrapPolicy.sources[index]}\n").toByteArray()
                    }
                    id == "stat_before" || id == "stat_after" -> bytes = OemBootstrapPolicyTest.statBytes(pid!!, if (pid == 123) "strategyservice" else if (pid == 1) "init" else "app_process", "77")
                    id == "cmdline" -> bytes = byteArrayOf(47, 105, 110, 105, 116, 0, -1, 0)
                    else -> bytes = "fixture\n".toByteArray()
                }
                OemBootstrapPolicy.Blob(id, cap, status, if (status == "ok") 0 else 1, bytes)
            }
        }
        override fun chunk(entry: OemBootstrapPolicy.Source, offset: Long, length: Int): ByteArray {
            reads++; requested += minOf(length.toLong(), entry.stamp!!.size - offset)
            val bytes = payloads[entry.index] ?: throw IOException("source denied")
            return bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length))
        }
    }
    private fun collector(device: OemBootstrapExport.Device, dir: File, cancelled: () -> Boolean = { false },
                          free: (File) -> Long = { Long.MAX_VALUE }, clock: () -> Long = FullApkExport::monotonicMs,
                          progress: (FullApkExport.Progress) -> Unit = {}) = OemBootstrapExport(device, dir, cancelled, progress, free, clock)
    private fun manifest(dir: File) = JSONObject(File(dir, "manifest.json").readText())
    @Test fun `binary original bytes empty source raw NUL proc and explicit denied candidates survive ZIP with hashes`() {
        val fake = Fake(); val dir = temporary.newFolder(); val result = collector(fake, dir).run()
        assertEquals("partial", result.outcome); assertEquals(2, fake.reads)
        val report = manifest(dir); assertEquals(3, report.getJSONArray("processes").length())
        assertEquals(OemBootstrapPolicy.sources.size, report.getJSONArray("files").length())
        ZipFile(result.archives.single()).use { zip ->
            for ((index, bytes) in fake.payloads) {
                val record = report.getJSONArray("files").getJSONObject(index)
                assertArrayEquals(bytes, zip.getInputStream(zip.getEntry(record.getString("zip_entry"))).readBytes())
                assertEquals(hash(bytes), record.getString("sha256")); assertEquals(bytes.size.toLong(), record.getLong("bytes"))
            }
            assertEquals(6, zip.size())
        }
        assertEquals("access_denied", report.getJSONArray("files").getJSONObject(2).getString("status"))
        assertEquals(hash(result.archives.single().readBytes()), JSONObject(File(dir, "integrite.json").readText()).getString("sha256"))
    }
    @Test fun `post call stop discards current metadata and retains only previously completed inventory`() {
        val stop = AtomicBoolean(false)
        val fake = object : Fake() {
            override fun metadata(label: String, pid: Int?): List<OemBootstrapPolicy.Blob> {
                val result = super.metadata(label, pid); if (label == "pid_1") stop.set(true); return result
            }
        }
        val dir = temporary.newFolder(); val result = collector(fake, dir, stop::get).run()
        assertEquals("cancelled", result.outcome); assertEquals(listOf("inventory", "pid_1"), fake.metadataCalls)
        assertEquals(0, manifest(dir).getJSONArray("processes").length()); assertEquals(0, fake.reads)
        assertEquals(OemBootstrapPolicy.inventoryLimits.size, manifest(dir).getJSONArray("inventory").length())
        assertEquals(1, result.archives.size)
    }
    @Test fun `post call stop discards just returned chunk before staging hash or source finalization`() {
        val stop = AtomicBoolean(false)
        val fake = object : Fake() {
            override fun chunk(entry: OemBootstrapPolicy.Source, offset: Long, length: Int): ByteArray {
                val bytes = super.chunk(entry, offset, length); stop.set(true); return bytes
            }
        }
        val dir = temporary.newFolder(); val result = collector(fake, dir, stop::get).run()
        assertEquals("cancelled", result.outcome); assertEquals(1, fake.reads)
        assertEquals("cancelled_incomplete", manifest(dir).getJSONArray("files").getJSONObject(0).getString("status"))
        assertEquals(0, File(dir, "source-000.bin.part").length())
        ZipFile(result.archives.single()).use { assertNull(it.getEntry("bootstrap/source-000.bin")) }
    }
    @Test fun `partial chunk cancellation leaves incomplete source outside finalized ZIP`() {
        val stop = AtomicBoolean(false)
        val fake = object : Fake(mapOf(0 to ByteArray(OemBootstrapPolicy.CHUNK + 1))) {
            override fun chunk(entry: OemBootstrapPolicy.Source, offset: Long, length: Int): ByteArray {
                val bytes = super.chunk(entry, offset, length); stop.set(true); return bytes
            }
        }
        val dir = temporary.newFolder(); val result = collector(fake, dir, stop::get).run()
        assertEquals("cancelled", result.outcome); assertEquals(1, fake.reads)
        assertEquals("cancelled_incomplete", manifest(dir).getJSONArray("files").getJSONObject(0).getString("status"))
        ZipFile(result.archives.single()).use { assertEquals(4, it.size()) }
        assertTrue(File(dir, "source-000.bin.part").isFile)
    }
    @Test fun `failed post stat reads consume aggregate requested byte quota and later candidates are explicitly limited`() {
        val payload = ByteArray(OemBootstrapPolicy.MAX_FILE_BYTES.toInt()) { 65 }
        val fake = object : Fake((0..5).associateWith { payload }) {
            override fun chunk(entry: OemBootstrapPolicy.Source, offset: Long, length: Int): ByteArray {
                val bytes = super.chunk(entry, offset, length)
                if (offset + bytes.size == entry.stamp!!.size) throw IOException("pread completed; post-stat changed")
                return bytes
            }
        }
        val dir = temporary.newFolder(); val result = collector(fake, dir).run()
        assertEquals("partial", result.outcome); assertEquals(OemBootstrapPolicy.MAX_SOURCE_BYTES, fake.requested)
        val report = manifest(dir); assertEquals(fake.requested, report.getLong("source_read_bytes")); assertEquals(0L, report.getLong("source_bytes"))
        assertEquals("total_source_read_byte_limit", report.getJSONArray("files").getJSONObject(4).getString("status"))
        assertFalse(File(dir, "source-000.bin.part").exists())
    }
    @Test fun `oversize file is never read and independent empty file still opens strict source`() {
        val fake = Fake(mapOf(1 to byteArrayOf()), mapOf(0 to OemBootstrapPolicy.MAX_FILE_BYTES + 1))
        val dir = temporary.newFolder(); collector(fake, dir).run()
        assertEquals(1, fake.reads); assertEquals("file_byte_limit", manifest(dir).getJSONArray("files").getJSONObject(0).getString("status"))
        assertEquals("exported", manifest(dir).getJSONArray("files").getJSONObject(1).getString("status"))
    }
    @Test fun `maximal bounded proc expansion and all fixed batches stay within aggregate metadata budget`() {
        val fake = object : Fake() {
            override fun metadata(label: String, pid: Int?): List<OemBootstrapPolicy.Blob> = super.metadata(label, pid).map { b ->
                when {
                    b.id == "ps" -> b.copy(bytes = ("PID NAME\n" + (2..5).joinToString("\n") { "$it init" } + "\n" + (10..13).joinToString("\n") { "$it strategyservice" } + "\n").toByteArray())
                    label == "inventory" || (label.startsWith("pid_") && !b.id.startsWith("stat_")) -> b.copy(bytes = ByteArray(b.limit) { 65 })
                    else -> b
                }
            }
        }
        val dir = temporary.newFolder(); val result = collector(fake, dir).run()
        assertEquals("partial", result.outcome)
        assertEquals(10, manifest(dir).getJSONArray("processes").length())
        assertTrue(manifest(dir).getLong("metadata_bytes_charged") <= OemBootstrapPolicy.MAX_METADATA_BYTES)
        assertEquals(7, fake.metadataCalls.count { it.startsWith("sources_") })
        assertEquals(2, fake.reads); assertTrue(result.archives.single().isFile)
        val perPidWire = OemBootstrapPolicy.processLimits.entries.sumOf { (id, cap) -> ((cap + 130L) / 3 + 1) * 4 + id.length + 128 } + 256
        assertTrue("Per-PID expanded metadata exceeded 64KiB", perPidWire < 64 * 1024)
    }
    @Test fun `later source metadata timeout retains first batch copies and never submits another operation`() {
        val fake = object : Fake() {
            var terminal = false
            override val terminated get() = terminal
            override fun metadata(label: String, pid: Int?): List<OemBootstrapPolicy.Blob> {
                if (label == "sources_4") {
                    metadataCalls.add(label); terminal = true; throw IOException("Physical command timed out after earlier useful batch")
                }
                return super.metadata(label, pid)
            }
        }
        val dir = temporary.newFolder(); val result = collector(fake, dir).run()
        assertEquals("partial", result.outcome); assertEquals("sources_4", fake.metadataCalls.last()); assertEquals(2, fake.reads)
        assertTrue(File(dir, "source-000.bin").isFile)
        ZipFile(result.archives.single()).use { assertNotNull(it.getEntry("bootstrap/source-000.bin")); assertEquals(6, it.size()) }
        assertEquals("not_attempted", manifest(dir).getJSONArray("files").getJSONObject(4).getString("status"))
    }
    @Test fun `delayed chunk return after stop or deadline cannot stage or hash its bytes and retains earlier file`() {
        for (mode in listOf("cancel", "deadline")) {
            val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
            val now = java.util.concurrent.atomic.AtomicLong(0); val stop = AtomicBoolean(false)
            val fake = object : Fake(mapOf(0 to byteArrayOf(1), 1 to byteArrayOf(2, 3, 4))) {
                override fun chunk(entry: OemBootstrapPolicy.Source, offset: Long, length: Int): ByteArray {
                    if (entry.index == 1) { entered.countDown(); check(release.await(3, java.util.concurrent.TimeUnit.SECONDS)) }
                    return super.chunk(entry, offset, length)
                }
            }
            val dir = temporary.newFolder(); val result = java.util.concurrent.atomic.AtomicReference<FullApkExport.Progress>()
            val worker = Thread { result.set(collector(fake, dir, stop::get, clock = now::get).run()) }
            worker.start()
            try {
                assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)); assertTrue(File(dir, "source-000.bin").isFile)
                if (mode == "cancel") stop.set(true) else now.set(OemBootstrapPolicy.COLLECT_MS + 1)
            } finally { release.countDown(); worker.join(5000) }
            assertFalse(worker.isAlive); assertEquals(if (mode == "cancel") "cancelled" else "partial", result.get().outcome)
            val second = manifest(dir).getJSONArray("files").getJSONObject(1)
            assertEquals(0L, second.getLong("bytes")); assertEquals("", second.getString("sha256")); assertFalse(File(dir, "source-001.bin").exists())
            ZipFile(result.get().archives.single()).use { assertNotNull(it.getEntry("bootstrap/source-000.bin")); assertNull(it.getEntry("bootstrap/source-001.bin")) }
        }
    }
    @Test fun `truncated metadata retains conservative wire charge even when decoded bytes omit128 byte prefix tail`() {
        val physicalWire = buildString {
            append("BEGIN|OEM_BOOTSTRAP_V1|inventory\n")
            OemBootstrapPolicy.inventoryLimits.forEach { (id, cap) ->
                val encoded = java.util.Base64.getEncoder().encodeToString(ByteArray(cap + 128) { 65 })
                append("BLOB|$id|$cap|$encoded\n")
            }
            append("END|OEM_BOOTSTRAP_V1|inventory\n")
        }
        val stop = AtomicBoolean(false)
        val fake = object : Fake() {
            override fun metadata(label: String, pid: Int?): List<OemBootstrapPolicy.Blob> {
                metadataCalls.add(label); assertEquals("inventory", label)
                return OemBootstrapPolicy.parse(label, physicalWire, OemBootstrapPolicy.inventoryLimits)
            }
        }
        val dir = temporary.newFolder()
        val result = collector(fake, dir, stop::get, progress = { if (it.stage.startsWith("Identité des processus")) stop.set(true) }).run()
        val report = manifest(dir)
        assertEquals("cancelled", result.outcome); assertEquals(listOf("inventory"), fake.metadataCalls)
        assertTrue("Truncated metadata was charged below original consumed wire", report.getLong("metadata_bytes_charged") >= physicalWire.toByteArray().size)
        val first = report.getJSONArray("inventory").getJSONObject(0)
        assertEquals(32768, first.getInt("bytes")); assertEquals("truncated_exit_unobserved", first.getString("status"))
        assertTrue(report.getLong("metadata_bytes_charged") <= OemBootstrapPolicy.MAX_METADATA_BYTES)
    }
    @Test fun `failed physical metadata call keeps entire precharged bound without any refund or next call`() {
        val fake = object : Fake() {
            override fun metadata(label: String, pid: Int?): List<OemBootstrapPolicy.Blob> {
                metadataCalls.add(label); throw IOException("physical metadata command failed without returned output")
            }
        }
        val dir = temporary.newFolder(); val result = collector(fake, dir).run()
        // Contract includes each encoded cap plus128 bytes of possible prefix/exit data and
        // framing allowance, even though no bytes were returned to the caller.
        val reserved = OemBootstrapPolicy.inventoryLimits.entries.sumOf { (id, cap) -> ((cap + 130L) / 3 + 1) * 4 + id.length + 128 } + 256
        assertEquals("partial", result.outcome); assertEquals(listOf("inventory"), fake.metadataCalls)
        assertEquals(reserved, manifest(dir).getLong("metadata_bytes_charged")); assertEquals(0, fake.reads)
        assertTrue(result.archives.single().isFile)
    }
    @Test fun `deadline disk loss staged tampering and hash sidecar failure preserve completed local evidence`() {
        for (mode in listOf("deadline", "disk", "tamper", "sidecar")) {
            val dir = temporary.newFolder(); var time = 0L; var touched = false
            val fake = Fake()
            val result = collector(fake, dir, free = {
                if (File(dir, "manifest.json").exists()) {
                    if (mode == "disk") return@collector 1L
                    if (mode == "tamper" && !touched) { File(dir, "source-000.bin").writeBytes(byteArrayOf(4, 5, 6)); touched = true }
                    if (mode == "sidecar" && File(dir, "oem-bootstrap-001.zip").exists()) return@collector 1L
                }
                Long.MAX_VALUE
            }, clock = { time }, progress = { if (mode == "deadline" && it.stage == OemBootstrapExport.FINALIZING) time = OemBootstrapPolicy.SESSION_MS + 1 }).run()
            assertEquals("failed", result.outcome); assertTrue(File(dir, "source-000.bin").isFile)
            assertEquals(if (mode == "sidecar") 1 else 0, result.archives.size)
            if (mode == "tamper") assertTrue(touched)
        }
    }
    @Test fun `cancellation during local finalization does not interrupt useful ZIP delivery`() {
        val stop = AtomicBoolean(false); val dir = temporary.newFolder()
        val result = collector(Fake(), dir, stop::get, progress = { if (it.stage == OemBootstrapExport.FINALIZING) stop.set(true) }).run()
        assertTrue(stop.get()); assertEquals("partial", result.outcome); assertEquals(1, result.archives.size)
    }
    @Test fun `previous exports are never overwritten and bootstrap recovery history and primary URI sharing retain identity`() {
        val dir = temporary.newFolder(); File(dir, "existing.bin").writeBytes(byteArrayOf(1))
        assertEquals("failed", collector(Fake(), dir).run().outcome); assertEquals(listOf("existing.bin"), dir.list()!!.toList())
        val base = temporary.newFolder(); val previous = File(base, "oem-bootstrap-20261017-120000-000").apply { mkdir() }
        File(previous, "oem-bootstrap-001.zip").writeBytes(byteArrayOf(1)); File(previous, "ERREUR_LIVRAISON.txt").writeText("hash failed")
        val history = FullApkExportSession.previousOemReports(listOf(ExportKind.OEM_BOOTSTRAP to base))
        assertEquals(1, history.entries.size)
        val recovered = FullApkExportSession.recoverPrevious(ExportKind.OEM_BOOTSTRAP, previous)
        assertEquals("failed", recovered.outcome); assertEquals(ExportKind.OEM_BOOTSTRAP, recovered.kind); assertEquals(1, recovered.archives.size)
        val app = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(app) { override fun getExternalFilesDir(type: String?): File? = throw AssertionError("Fallback called") }
        val method = FullApkExportSession::class.java.getDeclaredMethod("exportRoot", Context::class.java, ExportKind::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val root = method.invoke(FullApkExportSession, context, ExportKind.OEM_BOOTSTRAP, false) as File
        assertEquals("/storage/emulated/${android.os.Process.myUid() / 100000}/Android/data/${app.packageName}/files/oem-bootstrap-exports", root.path)
        val primary = object : ContextWrapper(app) { override fun getExternalFilesDirs(type: String?): Array<File> = arrayOf(root.parentFile!!) }
        assertEquals("content", FileProvider.getUriForFile(primary, "${app.packageName}.fileprovider", File(root, "oem-bootstrap-001.zip")).scheme)
    }
    @Test fun `bootstrap UI offers specific collection history and deferred stop with finalization disabled`() {
        val field = FullApkExportSession::class.java.getDeclaredField("snapshot").apply { isAccessible = true }; val before = field.get(null)
        val cancellation = FullApkExportSession::class.java.getDeclaredField("cancellation").apply { isAccessible = true }.get(null) as AtomicBoolean
        val previousCancellation = cancellation.getAndSet(false)
        val controller = Robolectric.buildActivity(DiagActivity::class.java).create()
        try {
            val activity = controller.get()
            val render = DiagActivity::class.java.getDeclaredMethod("renderFullExport").apply { isAccessible = true }
            val state = FullApkExport.Progress("Identité des processus", kind = ExportKind.OEM_BOOTSTRAP, directory = temporary.newFolder())
            field.set(null, state); render.invoke(activity)
            val buttons = mutableListOf<Button>()
            val texts = mutableListOf<TextView>()
            fun visit(v: View) { if (v is Button) buttons.add(v); if (v is TextView) texts.add(v); if (v is ViewGroup) repeat(v.childCount) { visit(v.getChildAt(it)) } }
            visit(activity.window.decorView)
            val cancel = buttons.single { it.text.toString() == "Cancel export" }
            assertTrue(cancel.isEnabled); assertEquals("Stop export reads", cancel.contentDescription)
            cancel.performClick()
            assertTrue(cancellation.get()); assertFalse(cancel.isEnabled)
            assertSame(state, FullApkExportSession.snapshot)
            assertTrue(texts.any { it.text.toString().contains("Cancellation pending") })
            assertTrue(texts.any { it.text.toString().contains("Arrêt des lectures après leur retour ; puis finalisation locale") })
            cancellation.set(false)
            field.set(null, state.copy(stage = OemBootstrapExport.FINALIZING)); render.invoke(activity); assertFalse(cancel.isEnabled)
            assertEquals("Cancel unavailable during local finalization", cancel.contentDescription)
            assertTrue(texts.any { it.text.toString().contains(OemBootstrapExport.FINALIZING) })
            DiagActivity::class.java.getDeclaredMethod("chooseOemMetadata").apply { isAccessible = true }.invoke(activity)
            val menu = ShadowAlertDialog.getLatestAlertDialog().listView
            assertTrue((0 until menu.adapter.count).any { menu.adapter.getItem(it).toString() == "Démarrage et identité du service OEM" })
            val history = FullApkExportSession.OemHistory(listOf(ExportKind.OEM_BOOTSTRAP to temporary.newFolder("oem-bootstrap-20261017-120000-000")), false, false)
            DiagActivity::class.java.getDeclaredMethod("showOemHistory", FullApkExportSession.OemHistory::class.java).apply { isAccessible = true }.invoke(activity, history)
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().listView.adapter.getItem(0).toString().contains("Démarrage OEM"))
        } finally { field.set(null, before); cancellation.set(previousCancellation); controller.destroy() }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
