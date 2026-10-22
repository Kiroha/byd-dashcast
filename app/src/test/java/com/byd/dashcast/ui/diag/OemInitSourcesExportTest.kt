package com.byd.dashcast.ui.diag

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.FileProvider
import com.byd.dashcast.proxy.daemon.StrictFileRead
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
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OemInitSourcesExportTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Fake(val payloads: List<ByteArray>, val beforeChunk: (Int) -> Unit = {}) : OemInitSourcesExport.Device {
        val entries = payloads.mapIndexed { i, bytes ->
            val path = "/init.source-$i.rc"
            OemInitSourcesPolicy.Entry(0, i + 1, Base64.getEncoder().encodeToString(path.toByteArray()), path, "ok",
                StrictFileRead.Expected(1, i + 100L, 0x81a4, bytes.size.toLong(), 1, 1))
        }
        var reads = 0
        override fun negotiate(check: () -> Unit): Map<String, String> { check(); check(); return mapOf("daemon_uid" to "2000") }
        override fun page(root: Int, offset: Int) = OemInitSourcesPolicy.Page(root, offset, "ok", "fixture",
            if (root == 0) entries else emptyList(), false, 100)
        override fun chunk(entry: OemInitSourcesPolicy.Entry, offset: Long, length: Int): ByteArray {
            val index = entries.indexOf(entry); beforeChunk(index); reads++
            return payloads[index].copyOfRange(offset.toInt(), minOf(payloads[index].size, offset.toInt() + length))
        }
    }
    private fun collector(device: OemInitSourcesExport.Device, dir: File, cancelled: () -> Boolean = { false },
        free: (File) -> Long = { Long.MAX_VALUE }, clock: () -> Long = FullApkExport::monotonicMs) =
        OemInitSourcesExport(device, dir, cancelled, {}, free, clock)
    @Test fun `large raw sources quoted services binary bytes and empty files survive byte identical with hashes`() {
        val content = ("service \"quoted\" /vendor/bin/service --secret\non boot\n    exec dangerous\n".repeat(1100)).toByteArray()
        val payloads = listOf(content, byteArrayOf(0, -1, 10, 13, 65), byteArrayOf())
        val fake = Fake(payloads); val dir = temporary.newFolder()
        val result = collector(fake, dir).run()
        assertEquals("finished", result.outcome); assertEquals(3, result.completed); assertTrue(fake.reads >= 4)
        val manifest = JSONObject(File(dir, "manifest.json").readText())
        ZipFile(result.archives.single()).use { zip ->
            for (i in payloads.indices) {
                val record = manifest.getJSONArray("files").getJSONObject(i)
                val bytes = zip.getInputStream(zip.getEntry(record.getString("zip_entry"))).readBytes()
                assertArrayEquals(payloads[i], bytes)
                assertEquals(hash(bytes), record.getString("sha256"))
                assertEquals(bytes.size.toLong(), record.getLong("bytes"))
            }
        }
        val integrity = JSONObject(File(dir, "integrite.json").readText())
        assertEquals(hash(result.archives.single().readBytes()), integrity.getString("sha256"))
    }
    @Test fun `ordinary source denial retains failure and still copies independent later source`() {
        val fake = Fake(listOf(byteArrayOf(1), byteArrayOf(2))) { if (it == 0) throw IOException("source denied") }
        val dir = temporary.newFolder(); val result = collector(fake, dir).run()
        assertEquals("partial", result.outcome); assertEquals(1, result.completed)
        assertTrue(result.archives.single().isFile)
        val files = JSONObject(File(dir, "manifest.json").readText()).getJSONArray("files")
        assertEquals("read_failed", files.getJSONObject(0).getString("status"))
        ZipFile(result.archives.single()).use { assertEquals(5, it.size()) }
        assertFalse(File(dir, "source-0000.rc.part").exists())
    }
    @Test fun `cancellation between source reads archives only previously completed stages`() {
        val stop = AtomicBoolean(false)
        val fake = Fake(listOf(byteArrayOf(1), byteArrayOf(2))) { if (it == 1) stop.set(true) }
        val dir = temporary.newFolder(); val result = collector(fake, dir, stop::get).run()
        assertEquals("cancelled", result.outcome); assertEquals(1, result.completed)
        ZipFile(result.archives.single()).use { zip ->
            assertNotNull(zip.getEntry("init-sources/source-0000.rc"))
            assertNull(zip.getEntry("init-sources/source-0001.rc"))
        }
    }
    @Test fun `disk or finalization deadline failure preserves completed stages and does not invent a ZIP`() {
        for (mode in listOf("space", "deadline")) {
            var time = 0L; val dir = temporary.newFolder(); val fake = Fake(listOf(ByteArray(3)))
            val forwarding = object : OemInitSourcesExport.Device by fake {
                override fun page(root: Int, offset: Int): OemInitSourcesPolicy.Page {
                    if (root == 0 && fake.reads > 0) {
                        if (mode == "deadline") time = OemInitSourcesPolicy.SESSION_MS + 1
                    }
                    return fake.page(root, offset)
                }
            }
            val result = collector(forwarding, dir, free = {
                if (mode == "space" && File(dir, "source-0000.rc").exists()) 1 else Long.MAX_VALUE
            }, clock = { time }).run()
            assertEquals("failed", result.outcome); assertTrue(result.archives.isEmpty())
            assertTrue(File(dir, "source-0000.rc").isFile)
        }
    }
    @Test fun `prior exports are retained and history includes new source profile without changing active state`() {
        val dir = temporary.newFolder(); File(dir, "existing.rc").writeText("keep")
        val result = collector(Fake(listOf(byteArrayOf(1))), dir).run()
        assertEquals("failed", result.outcome); assertEquals(listOf("existing.rc"), dir.list()!!.toList())
        val bases = listOf(ExportKind.QR_DIAGNOSTIC, ExportKind.OEM_SERVICE, ExportKind.OEM_INIT_SOURCES).map { kind ->
            val base = temporary.newFolder()
            File(base, "${kind.prefix}20261017-120000-000").mkdir()
            kind to base
        }
        val history = FullApkExportSession.previousOemReports(bases)
        assertEquals(3, history.entries.size); assertEquals(bases.map { it.first }.toSet(), history.entries.map { it.first }.toSet())
    }
    @Test fun `failed physical read attempts reserve their requested bytes before any returned data`() {
        var requested = 0L; var reads = 0
        val entries = (0 until 600).map { index ->
            val path = "/init.failure-$index.rc"
            OemInitSourcesPolicy.Entry(0, index + 1, Base64.getEncoder().encodeToString(path.toByteArray()), path, "ok",
                StrictFileRead.Expected(1, index + 1L, 0x81a4, 2L * 1024 * 1024, 1, 1))
        }
        val device = object : OemInitSourcesExport.Device {
            override fun negotiate(check: () -> Unit): Map<String, String> { check(); return emptyMap() }
            override fun page(root: Int, offset: Int): OemInitSourcesPolicy.Page {
                val selected = if (root == 0) entries.drop(offset).take(16) else emptyList()
                return OemInitSourcesPolicy.Page(root, offset, "ok", "fixture", selected, root == 0 && offset + 16 < entries.size, 100)
            }
            override fun chunk(entry: OemInitSourcesPolicy.Entry, offset: Long, length: Int): ByteArray {
                requested += length; reads++
                throw IOException("pread completed but post-stat changed; no bytes returned")
            }
        }
        val dir = temporary.newFolder(); val result = collector(device, dir).run()
        assertEquals("partial", result.outcome); assertEquals(0, result.completed)
        assertTrue("Failed operations exceeded the aggregate source budget", requested <= OemInitSourcesPolicy.MAX_SOURCE_BYTES)
        assertTrue("Budget did not stop independent failed source reads", reads in 1 until entries.size)
        val manifest = JSONObject(File(dir, "manifest.json").readText())
        assertEquals(requested, manifest.getLong("source_read_bytes"))
        assertTrue((0 until manifest.getJSONArray("files").length()).any {
            manifest.getJSONArray("files").getJSONObject(it).getString("status") == "total_source_read_byte_limit"
        })
    }
    @Test fun `source UI distinguishes acquisition stop from noncancellable local finalization and shows source history`() {
        val field = FullApkExportSession::class.java.getDeclaredField("snapshot").apply { isAccessible = true }
        val before = field.get(null)
        val cancellation = FullApkExportSession::class.java.getDeclaredField("cancellation").apply { isAccessible = true }.get(null) as AtomicBoolean
        val previousCancellation = cancellation.getAndSet(false)
        val controller = Robolectric.buildActivity(DiagActivity::class.java).create()
        try {
            val activity = controller.get()
            val render = DiagActivity::class.java.getDeclaredMethod("renderFullExport").apply { isAccessible = true }
            val state = FullApkExport.Progress("Copie source 1/2", kind = ExportKind.OEM_INIT_SOURCES, directory = temporary.newFolder())
            field.set(null, state); render.invoke(activity)
            val buttons = mutableListOf<Button>()
            val texts = mutableListOf<TextView>()
            fun visit(view: View) { if (view is Button) buttons.add(view); if (view is TextView) texts.add(view); if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) } }
            visit(activity.window.decorView)
            val cancel = buttons.single { it.text.toString() == "Cancel export" }
            assertTrue(cancel.isEnabled)
            assertEquals("Stop export reads and keep acquired files", cancel.contentDescription)
            cancel.performClick()
            assertTrue(cancellation.get()); assertFalse(cancel.isEnabled)
            assertSame(state, FullApkExportSession.snapshot)
            assertTrue(texts.any { it.text.toString().contains("Cancellation pending") })
            assertTrue(texts.any { it.text.toString().contains("Arrêt des lectures après leur retour ; puis finalisation locale") })
            cancellation.set(false)
            field.set(null, state.copy(stage = "Création du ZIP des sources déjà acquises")); render.invoke(activity)
            assertFalse(cancel.isEnabled)
            assertEquals("Cancel unavailable during local finalization", cancel.contentDescription)
            assertTrue(texts.any { it.text.toString().contains("Création du ZIP des sources déjà acquises") })
            val history = FullApkExportSession.OemHistory(listOf(ExportKind.OEM_INIT_SOURCES to temporary.newFolder("oem-init-sources-20261017-120000-000")), false, false)
            DiagActivity::class.java.getDeclaredMethod("showOemHistory", FullApkExportSession.OemHistory::class.java)
                .apply { isAccessible = true }.invoke(activity, history)
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().listView.adapter.getItem(0).toString().contains("Sources init OEM"))
        } finally { field.set(null, before); cancellation.set(previousCancellation); controller.destroy() }
    }
    @Test fun `new profile always uses primary emulated output without external storage fallback`() {
        val app = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(app) {
            override fun getExternalFilesDir(type: String?): File? = throw AssertionError("External storage fallback invoked")
        }
        val method = FullApkExportSession::class.java.getDeclaredMethod("exportRoot", Context::class.java, ExportKind::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val root = method.invoke(FullApkExportSession, context, ExportKind.OEM_INIT_SOURCES, false) as File
        assertEquals("/storage/emulated/${android.os.Process.myUid() / 100000}/Android/data/${app.packageName}/files/oem-init-source-exports", root.path)
        // Model the primary Android app-files root for the shipped FileProvider XML. This
        // verifies URI policy, not actual vehicle storage mapping or runtime read permissions.
        val primary = object : ContextWrapper(app) {
            override fun getExternalFilesDirs(type: String?): Array<File> = arrayOf(root.parentFile!!)
        }
        val uri = FileProvider.getUriForFile(primary, "${app.packageName}.fileprovider",
            File(root, "oem-init-sources-20261017-120000-000/oem-init-sources-001.zip"))
        assertEquals("content", uri.scheme)
        assertTrue(uri.path!!.endsWith("oem-init-sources-001.zip"))
    }
    @Test fun `changed staged source cannot produce a finalized ZIP with the original source hash`() {
        val dir = temporary.newFolder(); var changed = false
        val result = collector(Fake(listOf(byteArrayOf(1, 2, 3))), dir, free = {
            if (!changed && File(dir, "manifest.json").isFile) {
                File(dir, "source-0000.rc").writeBytes(byteArrayOf(4, 5, 6)); changed = true
            }
            Long.MAX_VALUE
        }).run()
        assertTrue(changed); assertEquals("failed", result.outcome); assertTrue(result.archives.isEmpty())
        assertTrue(File(dir, "oem-init-sources-001.zip.part").isFile)
        assertTrue(File(dir, "source-0000.rc").isFile)
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
