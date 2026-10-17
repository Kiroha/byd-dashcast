package com.byd.dashcast.ui.diag

import android.content.Context
import android.content.ContextWrapper
import android.os.Binder
import android.os.Parcel
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.FileProvider
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class QrDeveloperDiagnosticTest {
    @get:Rule val temporary = TemporaryFolder()
    private val profile = DiLinkContextPlan.Profile.QR_DIAGNOSTIC
    private val ample = { _: File -> Long.MAX_VALUE }

    private fun text(command: DiLinkContextPlan.Command): String = when (command.kind) {
        DiLinkContextPlan.Kind.ID -> "uid=2000(shell) gid=2000(shell)"
        DiLinkContextPlan.Kind.CURRENT_USER -> "10"
        DiLinkContextPlan.Kind.EPOCH -> "1792140000"
        DiLinkContextPlan.Kind.PROPERTY -> when (command.subject) {
            "sys.byd.countrycode" -> "33"
            "persist.sys.locale" -> "fr-FR"
            "persist.sys.timezone" -> "Europe/Paris"
            else -> "synthetic"
        }
        DiLinkContextPlan.Kind.PACKAGE_PATH -> "package:/system/app/${command.subject}/base.apk"
        DiLinkContextPlan.Kind.PACKAGE_DUMP -> "Packages:\n  Package [${command.subject}] (synthetic):\n    userId=1000\n    User 0: installed=true\n    User 10: installed=false"
        DiLinkContextPlan.Kind.SERVICES -> error("Service discovery is outside the QR profile")
        else -> error("New service-only kind outside this preserved profile")
    }

    @Test fun `QR profile is exactly sixteen fixed passive reads and preserves general fourteen`() {
        assertEquals(listOf(
            "id", "am get-current-user", "date +%s", "getprop sys.byd.countrycode",
            "getprop ro.build.type", "getprop ro.build.version.sdk", "getprop apps.setting.product.inswver",
            "getprop ro.build.region", "getprop persist.sys.locale", "getprop persist.sys.timezone",
            "pm path com.byd.byddevelopmenttools", "dumpsys package com.byd.byddevelopmenttools",
            "pm path com.byd.authservice", "dumpsys package com.byd.authservice",
            "pm path com.android.shell", "dumpsys package com.android.shell"), profile.commands.map { it.shell })
        assertEquals(16, profile.commands.map { it.key }.toSet().size)
        assertEquals(14, DiLinkContextPlan.commands.size)
        assertSame(DiLinkContextPlan.commands, DiLinkContextPlan.Profile.CONTEXT.commands)
        assertTrue(profile.commands.all { row -> row.argv.all { Regex("[A-Za-z0-9_./%+:-]+").matches(it) } })
        assertNotEquals(ExportKind.CONTEXT.rootName, ExportKind.QR_DIAGNOSTIC.rootName)
        assertNotEquals(ExportKind.CONTEXT.prefix, ExportKind.QR_DIAGNOSTIC.prefix)
    }

    @Test fun `QR device rejects fabricated mutators and commands from another profile before any transaction`() {
        var calls = 0
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                calls++; return false
            }
        }
        val device = DiLinkContextDevice(binder, profile)
        val mutations = listOf(
            listOf("am", "start", "-n", "com.byd.byddevelopmenttools/.MainActivity"),
            listOf("am", "broadcast", "-a", "test"), listOf("setprop", "sys.byd.countrycode", "86"),
            listOf("settings", "put", "global", "test", "1"), listOf("service", "call", "auth", "1"),
            listOf("content", "query", "--uri", "content://test"), listOf("pm", "grant", "test", "permission"),
            listOf("sh", "-c", "id;setprop x 1"))
        for (argv in mutations) {
            try { device.read(profile.commands.first().copy(argv = argv)); fail("Accepted $argv") }
            catch (_: IllegalArgumentException) { }
        }
        try { device.read(DiLinkContextPlan.commands.single { it.kind == DiLinkContextPlan.Kind.SERVICES }); fail("Wrong profile") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, calls)
    }

    @Test fun `current Android user remains distinct and unknown outputs never imply user zero or access`() {
        val command = profile.commands.single { it.kind == DiLinkContextPlan.Kind.CURRENT_USER }
        val observed = DiLinkContextPlan.interpret(command, 0, "10")
        assertEquals("ok", observed.status)
        assertEquals(10, observed.values["current_android_user"])
        assertTrue(observed.values["interpretation"].toString().contains("Launch rights remain untested"))
        for (bad in listOf("", "-1", "User 0", "10\n0", "2147483648", "true")) {
            assertEquals("unexpected_output", DiLinkContextPlan.interpret(command, 0, bad).status)
        }
        assertEquals("diagnostic_output", DiLinkContextPlan.interpret(command, 0, "Permission Denial: denied").status)
        assertEquals("truncated", DiLinkContextPlan.interpret(command, 0, "10\n[output truncated]").status)
    }

    @Test fun `QR ZIP and sidecar retain exact profile identity hashes dynamic progress and non-authorizing scope`() {
        val directory = temporary.newFolder()
        val states = mutableListOf<FullApkExport.Progress>()
        val seen = mutableListOf<DiLinkContextPlan.Command>()
        val result = DiLinkContextExport(DiLinkContextExport.Device { command ->
            seen.add(command); DiLinkContextTransport.Reply(0, text(command))
        }, directory, { false }, states::add, ample, profile = profile).run()
        assertEquals(profile.commands, seen)
        assertEquals(ExportKind.QR_DIAGNOSTIC, result.kind)
        assertEquals("finished", result.outcome)
        assertEquals(16, result.discovered)
        assertEquals(16, result.completed)
        assertEquals(0, result.failed)
        assertTrue(states.any { it.stage.startsWith("Lecture 16/16") })
        val archive = result.archives.single()
        assertEquals("qr-diagnostic-001.zip", archive.name)
        ZipFile(archive).use { zip ->
            for (name in listOf("plan.json", "resultats.json", "manifest.json")) {
                val bytes = zip.getInputStream(zip.getEntry(name)).readBytes()
                assertArrayEquals(File(directory, name).readBytes(), bytes)
                val metadata = JSONObject(String(bytes, Charsets.UTF_8))
                assertEquals(profile.id, metadata.getString("profile"))
                assertEquals("QR_DIAGNOSTIC", metadata.getString("kind"))
                assertEquals(16, metadata.getInt("planned"))
                assertEquals(profile.coverage, metadata.getString("coverage"))
            }
            val report = JSONObject(File(directory, "resultats.json").readText())
            assertEquals(10, report.getJSONArray("commands").getJSONObject(1).getJSONObject("observed").getInt("current_android_user"))
            assertEquals(16, report.getInt("attempted"))
            assertTrue(report.getJSONArray("limitations").toString().contains("no authorization bypass is demonstrated"))
            val manifest = JSONObject(File(directory, "manifest.json").readText())
            val files = manifest.getJSONArray("files")
            for (index in 0 until files.length()) {
                val file = files.getJSONObject(index)
                val bytes = zip.getInputStream(zip.getEntry(file.getString("name"))).readBytes()
                assertEquals(file.getLong("bytes"), bytes.size.toLong())
                assertEquals(file.getString("sha256"), sha(bytes))
            }
            assertNull(zip.getEntry("integrite.json"))
        }
        val integrity = JSONObject(File(directory, "integrite.json").readText())
        assertEquals(profile.id, integrity.getString("profile"))
        assertEquals("QR_DIAGNOSTIC", integrity.getString("kind"))
        assertEquals(sha(archive.readBytes()), integrity.getJSONObject("archive").getString("sha256"))
        assertTrue(File(directory, "RESULT.txt").readText().contains("16/16 lectures traitées"))
        assertTrue(File(directory, "RESULT.txt").readText().contains("Aucun code saisi ni QR validé"))
    }

    @Test fun `refused empty and malformed metadata remain partial and raw package user states stay intact`() {
        val directory = temporary.newFolder()
        val result = DiLinkContextExport(DiLinkContextExport.Device { command -> when {
            command.kind == DiLinkContextPlan.Kind.CURRENT_USER -> DiLinkContextTransport.Reply(0, "unknown")
            command.subject == "persist.sys.locale" -> DiLinkContextTransport.Reply(0, "")
            command.key == "dumpsys_package_authservice" -> DiLinkContextTransport.Reply(0, "Permission Denial: not allowed")
            else -> DiLinkContextTransport.Reply(0, text(command))
        } }, directory, { false }, {}, ample, profile = profile).run()
        assertEquals("partial", result.outcome)
        assertEquals(13, result.completed)
        assertEquals(3, result.failed)
        val report = JSONObject(File(directory, "resultats.json").readText())
        val statuses = report.getJSONArray("commands").let { rows ->
            (0 until rows.length()).associate { rows.getJSONObject(it).let { row -> row.getString("key") to row.getString("status") } }
        }
        assertEquals("unexpected_output", statuses["current_android_user"])
        assertEquals("value_absent", statuses["getprop_persist_sys_locale"])
        assertEquals("diagnostic_output", statuses["dumpsys_package_authservice"])
        assertTrue(File(directory, "12-dumpsys_package_tools.combined.txt").readText().contains("User 10: installed=false"))
    }

    @Test fun `QR cancellation and fatal transport preserve fifteen unattempted commands without retry`() {
        for (fatal in listOf(false, true)) {
            var stop = false
            var calls = 0
            val directory = temporary.newFolder()
            val result = DiLinkContextExport(DiLinkContextExport.Device { command ->
                calls++
                if (fatal) DiLinkContextTransport.Reply(null, "", "Pinned binder unavailable; no retry", true)
                else { stop = true; DiLinkContextTransport.Reply(0, text(command)) }
            }, directory, { stop }, {}, ample, profile = profile).run()
            assertEquals(1, calls)
            assertEquals(if (fatal) "partial" else "cancelled", result.outcome)
            val report = JSONObject(File(directory, "resultats.json").readText())
            assertEquals(16, report.getInt("planned"))
            assertEquals(1, report.getInt("attempted"))
            val rows = report.getJSONArray("commands")
            assertEquals(15, (0 until rows.length()).count { rows.getJSONObject(it).getString("status") == "not_attempted" })
            assertTrue(result.archives.single().isFile)
        }
    }

    @Test fun `QR session submits only fixed metadata TXN_EXEC then retains matching local archive metadata`() {
        val calls = mutableListOf<String>()
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(ProxyDaemonContract.TXN_EXEC, code)
                assertEquals(0, flags)
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                val shell = data.readString()
                val command = profile.commands.single { it.shell == shell }
                synchronized(calls) { calls.add(command.shell) }
                reply!!.writeNoException(); reply.writeInt(0); reply.writeString(text(command))
                return true
            }
        }
        val previousBinder = ProxyClient.sBinder
        val base = temporary.newFolder()
        val finished = CountDownLatch(1)
        try {
            ProxyClient.sBinder = binder
            FullApkExportSession.start(RuntimeEnvironment.getApplication(), ExportKind.QR_DIAGNOSTIC,
                root = { _, kind -> assertEquals(ExportKind.QR_DIAGNOSTIC, kind); base }) { finished.countDown() }
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            assertEquals(profile.commands.map { it.shell }, calls)
            val state = FullApkExportSession.snapshot!!
            assertEquals(ExportKind.QR_DIAGNOSTIC, state.kind)
            assertEquals("finished", state.outcome)
            assertTrue(state.directory!!.name.startsWith(ExportKind.QR_DIAGNOSTIC.prefix))
            assertFalse(File(state.directory, "RUNNING.txt").exists())
            ZipFile(state.archives.single()).use { zip ->
                for (name in listOf("RESULT.txt", "resultats.json", "manifest.json")) {
                    assertArrayEquals(File(state.directory, name).readBytes(), zip.getInputStream(zip.getEntry(name)).readBytes())
                }
            }
        } finally { ProxyClient.sBinder = previousBinder }
    }

    @Test fun `QR destination bypasses removable selection and existing fileprovider contains the modeled primary root`() {
        val app = RuntimeEnvironment.getApplication()
        val wrapped = object : ContextWrapper(app) {
            override fun getExternalFilesDir(type: String?): File = error("QR must never select an external volume")
        }
        val method = FullApkExportSession::class.java.getDeclaredMethod("exportRoot", Context::class.java, ExportKind::class.java, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        val root = method.invoke(FullApkExportSession, wrapped, ExportKind.QR_DIAGNOSTIC, false) as File
        assertEquals("/storage/emulated/${Process.myUid() / 100000}/Android/data/${app.packageName}/files/qr-diagnostic-exports", root.path)
        // Robolectric's default external-files directory is a host temporary path. Model the
        // Android primary app-files root explicitly; this checks the shipped FileProvider XML,
        // not the actual car's external-storage mapping or access permissions.
        val primaryProviderContext = object : ContextWrapper(app) {
            override fun getExternalFilesDirs(type: String?): Array<File> = arrayOf(root.parentFile!!)
        }
        val uri = FileProvider.getUriForFile(primaryProviderContext, "${app.packageName}.fileprovider",
            File(root, "qr-diagnostic-synthetic/qr-diagnostic-001.zip"))
        assertEquals("content", uri.scheme)
        assertTrue(uri.path!!.endsWith("qr-diagnostic-001.zip"))
    }

    @Test fun `failed QR startup clears previous archive identity and recovery retains the QR profile`() {
        val app = RuntimeEnvironment.getApplication()
        val previousBase = temporary.newFolder()
        val previousBinder = ProxyClient.sBinder
        try {
            ProxyClient.sBinder = null
            // The unavailable daemon still produces a bounded, correctly identified old context ZIP.
            FullApkExportSession.start(app, ExportKind.CONTEXT, launch = { task, _ -> task.run() },
                root = { _, _ -> previousBase }) { }
            val previous = FullApkExportSession.snapshot!!
            val previousZip = previous.archives.single()
            val before = sha(previousZip.readBytes())
            var releases = 0
            FullApkExportSession.start(app, ExportKind.QR_DIAGNOSTIC, launch = { task, _ -> task.run() },
                root = { _, _ -> throw java.io.IOException("Synthetic directory refusal") }) { releases++ }
            val failed = FullApkExportSession.snapshot!!
            assertEquals(ExportKind.QR_DIAGNOSTIC, failed.kind)
            assertEquals("failed", failed.outcome)
            assertNull(failed.directory)
            assertTrue(failed.archives.isEmpty())
            assertEquals(1, releases)
            assertEquals(before, sha(previousZip.readBytes()))

            val base = temporary.newFolder()
            val qrDirectory = File(base, "${ExportKind.QR_DIAGNOSTIC.prefix}20261017-123456-000").apply { mkdir() }
            val qrZip = File(qrDirectory, "qr-diagnostic-001.zip").apply { writeText("synthetic archive metadata fixture") }
            File(qrDirectory, "RESULT.txt").writeText("${profile.title}\nProfil : ${profile.id}")
            File(qrDirectory, "RUNNING.txt").writeText("interrupted")
            val recovered = FullApkExportSession.recoverPrevious(ExportKind.QR_DIAGNOSTIC, qrDirectory)
            assertEquals(ExportKind.QR_DIAGNOSTIC, recovered.kind)
            assertEquals("interrupted/partial", recovered.outcome)
            assertEquals(listOf(qrZip), recovered.archives)
            assertFalse(recovered.countsKnown)
            val olderBase = temporary.newFolder()
            File(olderBase, "${ExportKind.CONTEXT.prefix}20261016-123456-000").mkdir()
            val latest = ExportKind.latest(listOf(ExportKind.CONTEXT to olderBase, ExportKind.QR_DIAGNOSTIC to base))!!
            // Comparison is by timestamps, not the alphabetic order of the profile prefixes.
            assertEquals(ExportKind.QR_DIAGNOSTIC, latest.first)
            assertEquals(qrDirectory, latest.second)
            File(qrDirectory, "ERREUR_LIVRAISON.txt").writeText("integrity unavailable")
            assertEquals("failed", FullApkExportSession.recoverPrevious(ExportKind.QR_DIAGNOSTIC, qrDirectory).outcome)
        } finally { ProxyClient.sBinder = previousBinder }
    }

    @Test fun `QR screen action explains local metadata and cancellation never opens an OEM activity`() {
        val controller = Robolectric.buildActivity(DiagActivity::class.java).create()
        try {
            val activity = controller.get()
            val buttons = mutableListOf<Button>()
            fun visit(view: View) {
                if (view is Button) buttons.add(view)
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            visit(activity.window.decorView)
            assertEquals(1, buttons.count { it.text.toString() == "Export complet accessible" })
            buttons.single { it.text.toString() == "Relevés locaux OEM" }.performClick()
            val chooser = ShadowAlertDialog.getLatestAlertDialog().listView
            chooser.performItemClick(null, 0, 0)
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val shadow = org.robolectric.Shadows.shadowOf(dialog)
            assertTrue(shadow.message.toString().contains("16 lectures fixes"))
            assertTrue(shadow.message.toString().contains("Aucun outil OEM ouvert"))
            assertTrue(shadow.message.toString().contains("partage manuel"))
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
            assertNull(org.robolectric.Shadows.shadowOf(activity).nextStartedActivity)
        } finally { controller.destroy() }
    }

    @Test fun `QR share URI refusal keeps the selected archive immutable and reports a readable error`() {
        val directory = temporary.newFolder()
        val archive = File(directory, "qr-diagnostic-001.zip").apply { writeText("synthetic immutable ZIP fixture") }
        val before = sha(archive.readBytes())
        val field = FullApkExportSession::class.java.getDeclaredField("snapshot").apply { isAccessible = true }
        val previous = FullApkExportSession.snapshot
        field.set(null, FullApkExport.Progress("Synthetic finished metadata", directory = directory,
            archives = listOf(archive), running = false, outcome = "finished", discovered = 16,
            completed = 16, kind = ExportKind.QR_DIAGNOSTIC))
        val controller = Robolectric.buildActivity(DiagActivity::class.java).create()
        try {
            val activity = controller.get()
            val method = DiagActivity::class.java.getDeclaredMethod("showFullExportFiles").apply { isAccessible = true }
            method.invoke(activity)
            val first = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(org.robolectric.Shadows.shadowOf(first).message.toString().contains(profile.id))
            first.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val files = ShadowAlertDialog.getLatestAlertDialog().listView
            assertEquals(1, files.adapter.count)
            files.performItemClick(null, 0, 0)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(before, sha(archive.readBytes()))
            assertEquals(listOf(archive), directory.listFiles()!!.toList())
            assertNull(org.robolectric.Shadows.shadowOf(activity).nextStartedActivity)
            val texts = mutableListOf<String>()
            fun visit(view: View) {
                if (view is TextView) texts.add(view.text.toString())
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            visit(activity.window.decorView)
            assertTrue(texts.any { it.contains("Partage indisponible") && it.contains("Fichier local conservé") })
        } finally {
            controller.destroy()
            field.set(null, previous)
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
