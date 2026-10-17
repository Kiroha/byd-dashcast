package com.byd.dashcast.ui.diag

import android.content.Context
import android.content.ContextWrapper
import android.os.Binder
import android.os.Parcel
import android.os.Process
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OemServiceMetadataTest {
    @get:Rule val temporary = TemporaryFolder()
    private val profile = DiLinkContextPlan.Profile.OEM_SERVICE
    private val initCommand get() = profile.commands.single { it.kind == DiLinkContextPlan.Kind.INIT_METADATA }
    private val ample = { _: File -> Long.MAX_VALUE }

    private fun reply(command: DiLinkContextPlan.Command): String = when (command.kind) {
        DiLinkContextPlan.Kind.ID -> "uid=2000(shell) gid=2000(shell) context=u:r:shell:s0"
        DiLinkContextPlan.Kind.CURRENT_USER -> "0"
        DiLinkContextPlan.Kind.EPOCH -> "1792220000"
        DiLinkContextPlan.Kind.PROPERTY -> "synthetic"
        DiLinkContextPlan.Kind.SERVICES -> "Found 1 services:\n0 strategyservice: [example.IStrategy]"
        DiLinkContextPlan.Kind.SERVICE_CHECK -> "Service strategyservice: found"
        DiLinkContextPlan.Kind.PROCESS_METADATA -> "UID PID PPID NAME\n1000 200 1 unknown-publisher"
        DiLinkContextPlan.Kind.PATH_METADATA -> "synthetic literal metadata"
        DiLinkContextPlan.Kind.INIT_METADATA -> "BEGIN OEM_INIT_METADATA_V1\nTOTAL files=0 source_bytes=0\nEND SCOPED_COMPLETE"
        else -> error("Not in OEM service profile")
    }

    @Test fun `closed service profile contains exactly metadata-only argv with no process arguments`() {
        assertEquals(23, profile.commands.size)
        assertEquals(23, profile.commands.map { it.key }.toSet().size)
        assertEquals(listOf("id", "am get-current-user", "date +%s"), profile.commands.take(3).map { it.shell })
        assertEquals(listOf("ro.build.fingerprint", "ro.build.version.sdk", "ro.build.type", "apps.setting.product.inswver", "sys.byd.countrycode", "init.svc.strategyservice"),
            profile.commands.filter { it.kind == DiLinkContextPlan.Kind.PROPERTY }.map { it.subject })
        assertEquals(listOf("ps", "-A", "-o", "UID,PID,PPID,NAME"), profile.commands.single { it.kind == DiLinkContextPlan.Kind.PROCESS_METADATA }.argv)
        assertEquals(listOf("service check strategyservice", "service list"), profile.commands.filter { it.argv.first() == "service" }.map { it.shell }.sorted())
        assertEquals(10, profile.commands.count { it.kind == DiLinkContextPlan.Kind.PATH_METADATA })
        assertTrue(profile.commands.filter { it.kind != DiLinkContextPlan.Kind.INIT_METADATA }.all { row -> row.argv.all { Regex("[A-Za-z0-9_./%+:,-]+").matches(it) } })
        assertEquals(listOf("sh", "-c", OemServiceMetadata.script), initCommand.argv)
        assertEquals(14, DiLinkContextPlan.commands.size)
        assertEquals(16, DiLinkContextPlan.Profile.QR_DIAGNOSTIC.commands.size)
        assertNotEquals(ExportKind.QR_DIAGNOSTIC.rootName, profile.kind.rootName)
        assertFalse(OemServiceMetadata.script.contains("eval "))
        assertFalse(OemServiceMetadata.script.contains("awk "))
        assertFalse(OemServiceMetadata.script.contains("/proc"))
    }

    @Test fun `device rejects altered scripts arbitrary argv and legacy command outside new plan`() {
        var calls = 0
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean { calls++; return false }
        }
        val device = DiLinkContextDevice(binder, profile)
        for (command in listOf(initCommand.copy(argv = listOf("sh", "-c", "id; setprop test 1")),
            initCommand.copy(argv = initCommand.argv + "extra"), DiLinkContextPlan.commands.last())) {
            try { device.read(command); fail("Accepted fabricated command") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, calls)
    }

    private fun fixtureScript(root: File): String {
        var script = OemServiceMetadata.script.replace("/init*.rc", "${root.path}/init*.rc")
        for (partition in listOf("system", "vendor", "odm", "product", "system_ext"))
            script = script.replace("/$partition/etc/init", "${root.path}/$partition/etc/init")
        return script
    }
    private fun runReader(root: File, transform: (String) -> String = { it }, createRoots: Boolean = true): String {
        if (createRoots) for (partition in listOf("system", "vendor", "odm", "product", "system_ext"))
            File(root, "$partition/etc/init/hw").mkdirs()
        val script = transform(fixtureScript(root))
        // Exercise the exact shipped argv serializer through a real POSIX shell. Test-only roots.
        val command = initCommand.copy(argv = listOf("sh", "-c", script)).shell
        val process = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
        return output.trimEnd('\n', '\r')
    }
    private fun initFile(root: File, name: String, text: String): File = File(root, "system/etc/init/$name").apply {
        parentFile!!.mkdirs(); writeText(text)
    }

    @Test fun `real shell scanner exports all service names paths and associated safe metadata without args or execution`() {
        val root = temporary.newFolder()
        val marker = File(root, "MUST_NOT_EXIST")
        initFile(root, "publisher.rc", """
            # secret-comment-value
            service unknown-publisher /vendor/bin/unknown-binary --token hidden-secret
                user system
                group system inet # hidden-group-comment
            	seclabel u:r:system_server:s0
                onrestart restart strategyservice
            on property:test=1
                user secret-event-user
                exec -- /system/bin/sh -c '${'$'}(touch ${marker.path})'
            service another /system/bin/another argument-secret
                user root
            import /vendor/etc/init/optional.rc
        """.trimIndent().replace("\\t", "\t"))
        val output = runReader(root)
        assertEquals(output, "ok", OemServiceMetadata.interpret(output).status)
        assertTrue(output.contains("unknown-publisher /vendor/bin/unknown-binary"))
        assertTrue(output.contains("another /system/bin/another"))
        assertTrue(output.contains(" user system"))
        assertTrue(output.contains(" group system inet"))
        assertTrue(output.contains(" seclabel u:r:system_server:s0"))
        assertTrue(output.contains("IMPORT_NOT_FOLLOWED"))
        for (secret in listOf("hidden-secret", "hidden-group-comment", "secret-comment-value", "secret-event-user", "argument-secret", "touch", "onrestart"))
            assertFalse("Unexpected source field $secret in $output", output.contains(secret))
        assertFalse(marker.exists())
    }

    @Test fun `symlinks FIFOs noncanonical roots oversized files and dynamic imports remain incomplete`() {
        val root = temporary.newFolder()
        val ordinary = initFile(root, "good.rc", "service good /system/bin/good\n    user system\n")
        val outside = temporary.newFile().apply { writeText("service PRIVATE /secret --credential PRIVATE\n") }
        Files.createSymbolicLink(File(ordinary.parentFile, "linked.rc").toPath(), outside.toPath())
        val oversized = initFile(root, "oversized.rc", "x".repeat(65537))
        val fifo = File(ordinary.parentFile, "fifo.rc")
        val mkfifo = ProcessBuilder("mkfifo", fifo.path).start(); assertEquals(0, mkfifo.waitFor())
        initFile(root, "dynamic.rc", "import /vendor/etc/init/${'$'}{ro.hardware}.rc\n")
        val output = runReader(root)
        assertEquals("incomplete_metadata", OemServiceMetadata.interpret(output).status)
        assertTrue(OemServiceMetadata.interpret(output).values.isEmpty())
        assertTrue(output.contains("symlink_file"))
        assertTrue(output.contains("nonregular_file"))
        assertTrue(output.contains("file_byte_limit"))
        assertTrue(output.contains("unresolved_import"))
        assertFalse(output.contains("PRIVATE"))
        assertTrue(oversized.exists())
    }

    @Test fun `empty root glob and empty early init directories do not stop later vendor declarations`() {
        val root = temporary.newFolder()
        File(root, "system/etc/init").mkdirs()
        val vendor = File(root, "vendor/etc/init/hw/service.rc").apply {
            parentFile!!.mkdirs(); writeText("service real-name /vendor/bin/publisher --not-exported\n    user system\n")
        }
        val output = runReader(root)
        assertEquals(output, "ok", OemServiceMetadata.interpret(output).status)
        assertTrue(output.contains("SERVICE ${vendor.path}:1 real-name /vendor/bin/publisher"))
        assertFalse(output.contains("--not-exported"))
        assertTrue(output.contains("TOTAL files=1"))
    }

    @Test fun `init section attribution follows tokens regardless of indentation and never attributes action fields`() {
        val root = temporary.newFolder()
        initFile(root, "indent.rc", "    service indented /vendor/bin/publisher\nuser system\ngroup system inet\n    on boot\nuser ACTION_SECRET\n    import /vendor/etc/init/optional.rc\nuser IMPORT_SECRET\nservice second /system/bin/second\nseclabel u:r:system_server:s0\n")
        val output = runReader(root)
        assertEquals(output, "ok", OemServiceMetadata.interpret(output).status)
        assertTrue(output.contains(" indented /vendor/bin/publisher"))
        assertTrue(output.contains(" user system"))
        assertTrue(output.contains(" group system inet"))
        assertTrue(output.contains(" seclabel u:r:system_server:s0"))
        assertFalse(output.contains("ACTION_SECRET")); assertFalse(output.contains("IMPORT_SECRET"))
    }

    @Test fun `unavailable init root never asserts absence and does not skip remaining roots`() {
        val root = temporary.newFolder()
        initFile(root, "one.rc", "service observed /system/bin/observed\n")
        val output = runReader(root, createRoots = false)
        assertTrue(output.contains("root_unavailable"))
        assertFalse(output.contains("ROOT_MISSING"))
        assertTrue(output.contains(" observed /system/bin/observed"))
        assertEquals("incomplete_metadata", OemServiceMetadata.interpret(output).status)
        assertTrue(OemServiceMetadata.interpret(output).values.isEmpty())
    }

    @Test fun `file count budget includes rejected links before any later ordinary file read`() {
        val root = temporary.newFolder()
        val ordinary = initFile(root, "z-last.rc", "service NOT_READ /system/bin/late\n")
        val private = temporary.newFile().apply { writeText("service PRIVATE /private --PRIVATE\n") }
        Files.createSymbolicLink(File(ordinary.parentFile, "a-first.rc").toPath(), private.toPath())
        val output = runReader(root, { value -> value.replace("-gt 512", "-gt 1") })
        assertTrue(output.contains("symlink_file"))
        assertTrue(output.contains("file_count_limit"))
        assertFalse(output.contains("NOT_READ")); assertFalse(output.contains("PRIVATE"))
        assertEquals("incomplete_metadata", OemServiceMetadata.interpret(output).status)
    }

    @Test fun `line byte count total file and output limits cannot claim complete observations`() {
        for ((text, reason) in listOf("x".repeat(1025) to "line_byte_limit", "# empty\n".repeat(513) to "line_count_limit",
            "service continued /system/bin/exe \\\n    --private\n" to "continuation")) {
            val root = temporary.newFolder(); initFile(root, "bounded.rc", text)
            val output = runReader(root)
            assertTrue(output, output.contains(reason))
            assertEquals("incomplete_metadata", OemServiceMetadata.interpret(output).status)
        }
        val root = temporary.newFolder(); initFile(root, "one.rc", "service one /system/bin/one\n")
        for ((transform, reason) in listOf<Pair<(String) -> String, String>>(
            ({ value: String -> value.replace("-gt 512", "-gt 0") }) to "file_count_limit",
            ({ value: String -> value.replace("-gt 8388608", "-gt 0") }) to "total_byte_limit",
            ({ value: String -> value.replace("-gt 196608", "-gt 1") }) to "output_limit")) {
            assertNotEquals(OemServiceMetadata.script, transform(OemServiceMetadata.script))
            val output = runReader(root, transform)
            assertTrue(output, output.contains(reason))
            assertNotEquals("ok", OemServiceMetadata.interpret(output).status)
        }
        val scoped = "BEGIN OEM_INIT_METADATA_V1\nTOTAL files=0 source_bytes=0\nEND SCOPED_COMPLETE"
        assertEquals("unexpected_output", OemServiceMetadata.interpret(scoped.removeSuffix("END SCOPED_COMPLETE")).status)
        assertEquals("unexpected_output", OemServiceMetadata.interpret("$scoped\n[output truncated]").status)
    }

    @Test fun `unknown elevated conflicting or malformed shell identities stop before all other metadata reads`() {
        for (identity in listOf("", "uid=0(root)", "uid=1000(system)", "uid=2000(shell) uid=unknown", "uid=2000(shell) euid=0(root)", "uid=2000(shell) euid=unknown", "uid=2000(shell) euid=2000 euid=0", "euid=2000(shell)", "uid=2000(shell) uid=2000")) {
            var calls = 0
            val directory = temporary.newFolder()
            val result = DiLinkContextExport(DiLinkContextExport.Device { calls++; DiLinkContextTransport.Reply(0, identity) },
                directory, { false }, {}, ample, profile = profile).run()
            assertEquals(identity, 1, calls)
            assertEquals("partial", result.outcome)
            assertEquals(0, result.completed)
            val report = JSONObject(File(directory, "resultats.json").readText())
            assertEquals("identity_refused", report.getJSONArray("commands").getJSONObject(0).getString("status"))
            assertEquals("not_attempted", report.getJSONArray("commands").getJSONObject(1).getString("status"))
        }
        assertTrue(DiLinkContextPlan.isExpectedShellIdentity("uid=2000(shell) euid=2000(shell) gid=2000"))
        assertTrue(DiLinkContextPlan.isExpectedShellIdentity("uid=2000(shell) gid=2000 context=u:r:shell:s0"))
    }

    @Test fun `service metadata archive retains own profile raw denials and matching hashes`() {
        val directory = temporary.newFolder()
        val result = DiLinkContextExport(DiLinkContextExport.Device { command ->
            if (command.key == "candidate_system_link") DiLinkContextTransport.Reply(1, "readlink: denied")
            else DiLinkContextTransport.Reply(0, reply(command))
        }, directory, { false }, {}, ample, profile = profile).run()
        assertEquals(ExportKind.OEM_SERVICE, result.kind)
        assertEquals("partial", result.outcome)
        assertEquals(22, result.completed)
        val archive = result.archives.single(); assertEquals("oem-service-001.zip", archive.name)
        ZipFile(archive).use { zip ->
            for (name in listOf("plan.json", "manifest.json", "resultats.json")) {
                val json = JSONObject(zip.getInputStream(zip.getEntry(name)).bufferedReader().readText())
                assertEquals(profile.id, json.getString("profile")); assertEquals("OEM_SERVICE", json.getString("kind"))
            }
            val files = JSONObject(File(directory, "manifest.json").readText()).getJSONArray("files")
            for (i in 0 until files.length()) {
                val entry = files.getJSONObject(i)
                val bytes = zip.getInputStream(zip.getEntry(entry.getString("name"))).readBytes()
                assertEquals(entry.getLong("bytes"), bytes.size.toLong())
                assertEquals(entry.getString("sha256"), sha(bytes))
            }
        }
        val sidecar = JSONObject(File(directory, "integrite.json").readText())
        assertEquals(sha(archive.readBytes()), sidecar.getJSONObject("archive").getString("sha256"))
        assertTrue(File(directory, "resultats.json").readText().contains("command_error"))
        assertTrue(directory.listFiles()!!.filter { it.name.endsWith(".combined.txt") }.any { it.readText() == "readlink: denied" })
    }

    @Test fun `service metadata forces primary output and recovers without relabeling previous QR archives`() {
        val app = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(app) { override fun getExternalFilesDir(type: String?): File = error("No removable selection") }
        val method = FullApkExportSession::class.java.getDeclaredMethod("exportRoot", Context::class.java, ExportKind::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val root = method.invoke(FullApkExportSession, context, ExportKind.OEM_SERVICE, false) as File
        assertEquals("/storage/emulated/${Process.myUid() / 100000}/Android/data/${app.packageName}/files/oem-service-exports", root.path)
        val directory = temporary.newFolder("oem-service-20261017-140000-000")
        val archive = File(directory, "oem-service-001.zip").apply { writeText("synthetic") }
        File(directory, "RUNNING.txt").writeText("synthetic interrupted")
        val recovered = FullApkExportSession.recoverPrevious(ExportKind.OEM_SERVICE, directory)
        assertEquals(ExportKind.OEM_SERVICE, recovered.kind)
        assertEquals("interrupted/partial", recovered.outcome)
        assertEquals(listOf(archive), recovered.archives)
        assertFalse(recovered.countsKnown)
    }

    @Test fun `one OEM entry offers two distinct profiles and cancelling service confirmation launches nothing`() {
        val controller = Robolectric.buildActivity(DiagActivity::class.java).create()
        try {
            val buttons = mutableListOf<Button>()
            fun visit(view: View) { if (view is Button) buttons.add(view); if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i)) }
            visit(controller.get().window.decorView)
            assertEquals(1, buttons.count { it.text.toString() == "Relevés locaux OEM" })
            buttons.single { it.text.toString() == "Relevés locaux OEM" }.performClick()
            val list = ShadowAlertDialog.getLatestAlertDialog().listView
            assertEquals(3, list.adapter.count)
            assertTrue(list.adapter.getItem(0).toString().contains("16 lectures"))
            assertTrue(list.adapter.getItem(1).toString().contains("23 lectures"))
            list.performItemClick(null, 1, 1)
            val confirm = ShadowAlertDialog.getLatestAlertDialog()
            val message = org.robolectric.Shadows.shadowOf(confirm).message.toString()
            assertTrue(message.contains("fichier temporaire PID")); assertTrue(message.contains("Aucune tentative d’authentification"))
            confirm.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
            assertNull(org.robolectric.Shadows.shadowOf(controller.get()).nextStartedActivity)
        } finally { controller.destroy() }
    }

    @Test fun `history preserves both profiles and partial archives without replacing active snapshot`() {
        val qrBase = temporary.newFolder("qr-history")
        val oemBase = temporary.newFolder("oem-history")
        val qr = File(qrBase, "qr-diagnostic-20261017-120000-000").apply { mkdir() }
        val oem = File(oemBase, "oem-service-20261017-140000-000").apply { mkdir() }
        val qrArchive = File(qr, "qr-diagnostic-001.zip").apply { writeText("synthetic older QR archive") }
        val oemArchive = File(oem, "oem-service-001.zip").apply { writeText("synthetic partial service archive") }
        File(oem, "RESULT.txt").writeText("${profile.title} : partial\n")
        File(qrBase, "qr-diagnostic-not-a-timestamp").mkdir()
        Files.createSymbolicLink(File(qrBase, "qr-diagnostic-20261017-160000-000").toPath(), oem.toPath())
        val before = FullApkExportSession.snapshot
        val beforeHash = sha(qrArchive.readBytes())
        val history = FullApkExportSession.previousOemReports(listOf(ExportKind.QR_DIAGNOSTIC to qrBase, ExportKind.OEM_SERVICE to oemBase))
        assertEquals(listOf(ExportKind.OEM_SERVICE to oem, ExportKind.QR_DIAGNOSTIC to qr), history.entries)
        assertFalse(history.limited)
        assertSame(before, FullApkExportSession.snapshot)
        val controller = Robolectric.buildActivity(DiagActivity::class.java).create()
        try {
            val activity = controller.get()
            val historyMethod = DiagActivity::class.java.getDeclaredMethod("showOemHistory", FullApkExportSession.OemHistory::class.java).apply { isAccessible = true }
            historyMethod.invoke(activity, history)
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(2, dialog.listView.adapter.count)
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
            assertSame(before, FullApkExportSession.snapshot)
            assertNull(org.robolectric.Shadows.shadowOf(activity).nextStartedActivity)
            val state = FullApkExportSession.recoverPrevious(ExportKind.OEM_SERVICE, oem)
            val filesMethod = DiagActivity::class.java.getDeclaredMethod("showFullExportFiles", FullApkExport.Progress::class.java).apply { isAccessible = true }
            filesMethod.invoke(activity, state)
            val files = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(org.robolectric.Shadows.shadowOf(files).message.toString().contains(profile.id))
            files.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val list = ShadowAlertDialog.getLatestAlertDialog().listView
            assertTrue((0 until list.adapter.count).any { list.adapter.getItem(it).toString().startsWith(oemArchive.name) })
            assertSame(before, FullApkExportSession.snapshot)
            assertEquals(beforeHash, sha(qrArchive.readBytes()))
        } finally { controller.destroy() }
    }

    @Test fun `history enumeration and visible entries have declared finite limits`() {
        val base = temporary.newFolder("bounded-history")
        for (i in 0 until 140) File(base, "qr-diagnostic-20261017-120000-${i.toString().padStart(3, '0')}").mkdir()
        val history = FullApkExportSession.previousOemReports(listOf(ExportKind.QR_DIAGNOSTIC to base))
        assertTrue(history.limited)
        assertEquals(20, history.entries.size)
        assertTrue(history.entries.all { it.first == ExportKind.QR_DIAGNOSTIC })
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
