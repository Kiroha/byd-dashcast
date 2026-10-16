package com.byd.dashcast.ui.diag

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DiLinkContextExportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val ample = { _: File -> Long.MAX_VALUE }

    private fun text(command: DiLinkContextPlan.Command): String = when (command.kind) {
        DiLinkContextPlan.Kind.ID -> "uid=2000(shell) gid=2000(shell)"
        DiLinkContextPlan.Kind.EPOCH -> "1792140000"
        DiLinkContextPlan.Kind.SERVICES -> "Found 2 services:\n0 byd_auth_service: [byd.auth]\n1 FissionHostSvc: []"
        DiLinkContextPlan.Kind.PROPERTY -> when (command.subject) {
            "sys.byd.countrycode" -> "33"
            "persist.sys.locale" -> "en-US"
            else -> "observed"
        }
        DiLinkContextPlan.Kind.PACKAGE_PATH -> "package:/system/app/${command.subject}/base.apk"
        DiLinkContextPlan.Kind.PACKAGE_DUMP -> "Packages:\n  Package [${command.subject}] (test):\n    userId=1000"
    }
    private fun device(replace: (DiLinkContextPlan.Command) -> DiLinkContextTransport.Reply? = { null }) =
        DiLinkContextExport.Device { command -> replace(command) ?: DiLinkContextTransport.Reply(0, text(command)) }

    @Test fun `fixed plan has exactly 14 metadata reads and no configurable shell text`() {
        assertEquals(14, DiLinkContextPlan.commands.size)
        assertEquals(14, DiLinkContextPlan.commands.map { it.key }.toSet().size)
        assertEquals(listOf("id", "date", "service", "getprop", "pm", "dumpsys"),
            DiLinkContextPlan.commands.map { it.argv.first() }.distinct())
        assertTrue(DiLinkContextPlan.commands.all { row -> row.argv.all { Regex("[A-Za-z0-9_./%+:-]+").matches(it) } })
        assertEquals(7, DiLinkContextPlan.commands.count { it.kind == DiLinkContextPlan.Kind.PROPERTY })
    }

    @Test fun `ZIP retains exact transformed output and hashes with full local plan provenance`() {
        val directory = temporary.newFolder()
        val states = mutableListOf<FullApkExport.Progress>()
        val result = DiLinkContextExport(device(), directory, { false }, { states.add(it) }, ample,
            appRuntime = mapOf("app_uid" to 10123, "android_api" to 29, "app_version_name" to "synthetic")).run()
        assertEquals("finished", result.outcome)
        assertEquals(14, result.completed)
        assertEquals(0, result.failed)
        assertEquals(ExportKind.CONTEXT, result.kind)
        val archive = result.archives.single()
        assertEquals("context-001.zip", archive.name)
        ZipFile(archive).use { zip ->
            val output = zip.getInputStream(zip.getEntry("01-shell_id.combined.txt")).readBytes()
            assertArrayEquals(text(DiLinkContextPlan.commands.first()).toByteArray(), output)
            val manifest = zip.getInputStream(zip.getEntry("manifest.json")).reader().readText()
            assertEquals(File(directory, "manifest.json").readText(), manifest)
            assertTrue(manifest.contains(sha(output)))
            assertNotNull(zip.getEntry("resultats.json"))
            assertNotNull(zip.getEntry("plan.json"))
            assertNotNull(zip.getEntry("RESULT.txt"))
            assertNull(zip.getEntry("integrite.json"))
        }
        val report = File(directory, "resultats.json").readText()
        assertTrue(report.contains("adb version (PC-only)"))
        assertTrue(report.contains("not original separate byte streams"))
        assertTrue(report.contains("\"observed_uid\":\"2000\""))
        assertTrue(report.contains("\"app_uid\":10123"))
        assertTrue(report.contains("en-US"))
        assertFalse(report.contains("vehicle_minus_host"))
        assertTrue(File(directory, "integrite.json").readText().contains(sha(archive.readBytes())))
        assertTrue(states.any { it.stage.startsWith("Lecture 14/14") })
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test fun `exit zero diagnostic permission and absent package never count as successful reads`() {
        val samples = listOf("Error: could not access package manager", "Permission Denial: not allowed",
            "java.lang.SecurityException: blocked", "Can't find service: package", "ERR unavailable")
        for (sample in samples) {
            val command = DiLinkContextPlan.commands.first { it.kind == DiLinkContextPlan.Kind.PACKAGE_DUMP }
            assertEquals("diagnostic_output", DiLinkContextPlan.interpret(command, 0, sample).status)
        }
        val result = DiLinkContextExport(device { command ->
            if (command.kind == DiLinkContextPlan.Kind.PACKAGE_DUMP) DiLinkContextTransport.Reply(0, "Unable to find package: ${command.subject}") else null
        }, temporary.newFolder(), { false }, {}, ample).run()
        assertEquals("partial", result.outcome)
        assertEquals(12, result.completed)
        assertEquals(2, result.failed)
        ZipFile(result.archives.single()).use { assertNotNull(it.getEntry("resultats.json")) }
    }

    @Test fun `empty locale stays unknown even with country 33 and malformed date is retained`() {
        val directory = temporary.newFolder()
        val result = DiLinkContextExport(device { command -> when {
            command.subject == "persist.sys.locale" -> DiLinkContextTransport.Reply(0, "")
            command.kind == DiLinkContextPlan.Kind.EPOCH -> DiLinkContextTransport.Reply(0, "+%s")
            else -> null
        } }, directory, { false }, {}, ample).run()
        assertEquals("partial", result.outcome)
        assertEquals(2, result.failed)
        val report = File(directory, "resultats.json").readText()
        assertTrue(report.contains("\"status\":\"value_absent\""))
        assertTrue(report.contains("no locale, country or property default is inferred"))
        assertTrue(report.contains("date +%s did not return an epoch integer"))
        assertFalse(report.contains("fr-FR"))
    }

    @Test fun `truncation and daemon timeout are partial and transport loss stops without retry`() {
        for (reply in listOf(DiLinkContextTransport.Reply(0, "header\n[output truncated]"),
            DiLinkContextTransport.Reply(-1, "ERR timeout 30s"), DiLinkContextTransport.Reply(13, "Permission denied"))) {
            val command = DiLinkContextPlan.commands.first()
            assertNotEquals("ok", DiLinkContextPlan.interpret(command, reply.exitCode, reply.combinedOutput).status)
        }
        var calls = 0
        val directory = temporary.newFolder()
        val result = DiLinkContextExport(DiLinkContextExport.Device {
            calls++
            if (calls == 1) DiLinkContextTransport.Reply(0, "uid=1000(system)")
            else DiLinkContextTransport.Reply(null, "", "Pinned Binder died; no retry", true)
        }, directory, { false }, {}, ample).run()
        assertEquals(2, calls)
        assertEquals("partial", result.outcome)
        assertEquals(1, result.completed)
        val report = File(directory, "resultats.json").readText()
        assertTrue(report.contains("\"exit_code\":null"))
        assertEquals(12, Regex("\\\"status\\\":\\\"not_attempted\\\"").findAll(report).count())
        assertTrue(report.contains("\"observed_uid\":\"1000\""))
    }

    @Test fun `cancellation between commands keeps usable ZIP and does not call any later command`() {
        var cancelled = false
        var calls = 0
        val result = DiLinkContextExport(DiLinkContextExport.Device { command ->
            calls++; cancelled = true; DiLinkContextTransport.Reply(0, text(command))
        }, temporary.newFolder(), { cancelled }, {}, ample).run()
        assertEquals(1, calls)
        assertEquals("cancelled", result.outcome)
        assertEquals(1, result.completed)
        ZipFile(result.archives.single()).use { zip ->
            val report = zip.getInputStream(zip.getEntry("resultats.json")).reader().readText()
            assertTrue(report.contains("\"outcome\":\"cancelled\""))
            assertEquals(13, Regex("\\\"status\\\":\\\"not_attempted\\\"").findAll(report).count())
        }
    }

    @Test fun `oversized transport output is bounded and marked truncated`() {
        val directory = temporary.newFolder()
        var calls = 0
        val result = DiLinkContextExport(device {
            calls++
            if (calls == 1) DiLinkContextTransport.Reply(0, "x".repeat(400_000)) else null
        }, directory, { false }, {}, ample).run()
        assertEquals("partial", result.outcome)
        assertTrue(File(directory, "01-shell_id.combined.txt").length() < 263_000)
        assertTrue(File(directory, "resultats.json").readText().contains("\"collector_clipped\":true"))
    }

    @Test fun `storage failure never overwrites a previous finished archive`() {
        val directory = temporary.newFolder()
        val old = File(directory, "context-001.zip").apply { writeText("previous export") }
        val oldReport = File(directory, "resultats.json").apply { writeText("previous report") }
        val result = DiLinkContextExport(device(), directory, { false }, {}, ample).run()
        assertEquals("failed", result.outcome)
        assertEquals("previous export", old.readText())
        assertEquals("previous report", oldReport.readText())
        assertTrue(result.archives.isEmpty())
        val full = DiLinkContextExport(device(), temporary.newFolder(), { false }, {}, { 1L }).run()
        assertEquals("failed", full.outcome)
        assertTrue(full.archives.isEmpty())
    }

    @Test fun `failed ZIP rename aligns external metadata and retains only an incomplete part`() {
        val directory = temporary.newFolder()
        val result = DiLinkContextExport(device(), directory, { false }, { state ->
            if (state.stage == "Création du ZIP local" && !File(directory, "context-001.zip").exists())
                assertTrue(File(directory, "context-001.zip").mkdir())
        }, ample).run()
        assertEquals("failed", result.outcome)
        assertTrue(result.archives.isEmpty())
        assertTrue(File(directory, "context-001.zip.part").isFile)
        assertTrue(File(directory, "resultats.json").readText().contains("\"outcome\":\"failed\""))
        val manifest = File(directory, "manifest.json").readText()
        assertTrue(manifest.contains("\"outcome\":\"failed\""))
        assertTrue(manifest.contains(sha(File(directory, "resultats.json").readBytes())))
        assertTrue(manifest.contains(sha(File(directory, "RESULT.txt").readBytes())))
    }

    @Test fun `integrity sidecar failure preserves immutable finished ZIP and explains delivery failure`() {
        val directory = temporary.newFolder()
        val result = DiLinkContextExport(device(), directory, { false }, { state ->
            if (state.stage == "Création du ZIP local" && !File(directory, "integrite.json").exists())
                assertTrue(File(directory, "integrite.json").mkdir())
        }, ample).run()
        assertEquals("failed", result.outcome)
        val archive = result.archives.single()
        ZipFile(archive).use { zip ->
            for (name in listOf("RESULT.txt", "resultats.json", "manifest.json")) {
                assertArrayEquals(File(directory, name).readBytes(), zip.getInputStream(zip.getEntry(name)).readBytes())
            }
            val report = zip.getInputStream(zip.getEntry("resultats.json")).reader().readText()
            assertTrue(report.contains("\"outcome\":\"finished\""))
            assertTrue(report.contains("Collection only; ZIP finalization and archive digest are attested separately"))
        }
        assertTrue(File(directory, "ERREUR_LIVRAISON.txt").readText().contains("ZIP finalisé conservé"))
        val recovered = FullApkExportSession.recoverPrevious(ExportKind.CONTEXT, directory)
        assertEquals("failed", recovered.outcome)
        assertFalse(recovered.running)
        assertFalse(recovered.countsKnown)
        assertEquals(listOf(archive), recovered.archives)
        assertTrue(recovered.stage.contains("ERREUR_LIVRAISON.txt"))
    }

    @Test fun `existing export profiles remain discoverable with context newest across kinds`() {
        val apk = temporary.newFolder("apk").apply { File(this, "apk-export-20261010").mkdir() }
        val system = temporary.newFolder("system").apply { File(this, "system-export-20261011").mkdir() }
        val context = temporary.newFolder("context").apply { File(this, "context-export-20261012").mkdir() }
        val latest = ExportKind.latest(listOf(ExportKind.APK to apk, ExportKind.SYSTEM to system, ExportKind.CONTEXT to context))!!
        assertEquals(ExportKind.CONTEXT, latest.first)
        assertEquals("context-export-20261012", latest.second.name)
    }

    @Test fun `late transport reply cannot mutate finalized partial ZIP or submit a second command`() {
        val transport = DiLinkContextTransport(timeoutMs = 30)
        val unblock = CountDownLatch(1)
        val idle = CountDownLatch(1)
        var calls = 0
        try {
            val result = DiLinkContextExport(DiLinkContextExport.Device {
                calls++
                transport.execute { unblock.await(); DiLinkContextTransport.Reply(0, "late uid=2000(shell)") }
            }, temporary.newFolder(), { false }, {}, ample).run()
            assertEquals("partial", result.outcome)
            assertEquals(1, calls)
            assertTrue(transport.isBusy())
            val archive = result.archives.single()
            val hashBefore = sha(archive.readBytes())
            transport.whenIdle { idle.countDown() }
            unblock.countDown()
            assertTrue(idle.await(1, TimeUnit.SECONDS))
            assertEquals(hashBefore, sha(archive.readBytes()))
            assertEquals(1, calls)
            ZipFile(archive).use { zip ->
                val report = zip.getInputStream(zip.getEntry("resultats.json")).reader().readText()
                assertTrue(report.contains("\"status\":\"transport_error\""))
                assertFalse(report.contains("late uid"))
            }
        } finally { unblock.countDown() }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
