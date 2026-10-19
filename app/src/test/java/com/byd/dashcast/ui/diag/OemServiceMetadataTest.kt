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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OemServiceMetadataTest {
    @get:Rule val temporary = TemporaryFolder()
    private val profile = DiLinkContextPlan.Profile.OEM_SERVICE
    private val initCommands get() = profile.commands.filter { it.kind == DiLinkContextPlan.Kind.INIT_METADATA }
    private val initCommand get() = initCommands.first()
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
        DiLinkContextPlan.Kind.INIT_METADATA -> completePage(command.subject.toInt())
        else -> error("Not in OEM service profile")
    }

    private fun completePage(page: Int = 1): String {
        val headers = OemServiceMetadata.script(page).lineSequence().filter { it.startsWith("emit '") }
            .take(4).map { it.removePrefix("emit '").removeSuffix("'") }.toList()
        return (headers + listOf("TOTAL files=0 selected=0 source_bytes=0",
            "PAGE_END index=$page count=32 first=${(page - 1) * 16 + 1} last=${page * 16}", "END SCOPED_COMPLETE")).joinToString("\n")
    }

    @Test fun `closed service profile contains exactly metadata-only argv with no process arguments`() {
        assertEquals(54, profile.commands.size)
        assertEquals(54, profile.commands.map { it.key }.toSet().size)
        assertEquals(listOf("id", "am get-current-user", "date +%s"), profile.commands.take(3).map { it.shell })
        assertEquals(listOf("ro.build.fingerprint", "ro.build.version.sdk", "ro.build.type", "apps.setting.product.inswver", "sys.byd.countrycode", "init.svc.strategyservice"),
            profile.commands.filter { it.kind == DiLinkContextPlan.Kind.PROPERTY }.map { it.subject })
        assertEquals(listOf("ps", "-A", "-o", "UID,PID,PPID,NAME"), profile.commands.single { it.kind == DiLinkContextPlan.Kind.PROCESS_METADATA }.argv)
        assertEquals(listOf("service check strategyservice", "service list"), profile.commands.filter { it.argv.first() == "service" }.map { it.shell }.sorted())
        assertEquals(10, profile.commands.count { it.kind == DiLinkContextPlan.Kind.PATH_METADATA })
        assertTrue(profile.commands.filter { it.kind != DiLinkContextPlan.Kind.INIT_METADATA }.all { row -> row.argv.all { Regex("[A-Za-z0-9_./%+:,-]+").matches(it) } })
        assertEquals(listOf("sh", "-c", OemServiceMetadata.script(1)), initCommand.argv)
        assertEquals(14, DiLinkContextPlan.commands.size)
        assertEquals(16, DiLinkContextPlan.Profile.QR_DIAGNOSTIC.commands.size)
        assertNotEquals(ExportKind.QR_DIAGNOSTIC.rootName, profile.kind.rootName)
        assertFalse(OemServiceMetadata.script(1).contains("eval "))
        assertFalse(OemServiceMetadata.script(1).contains("awk "))
        assertFalse(OemServiceMetadata.script(1).contains("/proc"))
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

    private fun fixtureScript(root: File, page: Int = 1): String {
        var script = OemServiceMetadata.script(page).replace("/init*.rc", "${root.path}/init*.rc")
        for (partition in listOf("system", "vendor", "odm", "product", "system_ext"))
            script = script.replace("/$partition/etc/init", "${root.path}/$partition/etc/init")
        return script
    }
    private fun runReader(root: File, transform: (String) -> String = { it }, createRoots: Boolean = true, page: Int = 1, environment: Map<String, String> = emptyMap()): String {
        if (createRoots) for (partition in listOf("system", "vendor", "odm", "product", "system_ext"))
            File(root, "$partition/etc/init/hw").mkdirs()
        val script = transform(fixtureScript(root, page))
        // Exercise the exact shipped argv serializer through a real POSIX shell. Test-only roots.
        val command = initCommand.copy(argv = listOf("sh", "-c", script)).shell
        val outputFile = temporary.newFile()
        val builder = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).redirectOutput(outputFile)
        builder.environment().putAll(environment)
        val process = builder.start()
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue("Reader did not return within test deadline", finished)
        val output = outputFile.readText()
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
        assertEquals(output, "ok", OemServiceMetadata.interpret(output, 1).status)
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
        assertEquals("incomplete_metadata", OemServiceMetadata.interpret(output, 1).status)
        assertTrue(OemServiceMetadata.interpret(output, 1).values.isEmpty())
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
        assertEquals(output, "ok", OemServiceMetadata.interpret(output, 1).status)
        assertTrue(output.contains("SERVICE ${vendor.path}:1 real-name /vendor/bin/publisher"))
        assertFalse(output.contains("--not-exported"))
        assertTrue(output.contains("TOTAL files=1"))
    }

    @Test fun `init section attribution follows tokens regardless of indentation and never attributes action fields`() {
        val root = temporary.newFolder()
        initFile(root, "indent.rc", "    service indented /vendor/bin/publisher\nuser system\ngroup system inet\n    on boot\nuser ACTION_SECRET\n    import /vendor/etc/init/optional.rc\nuser IMPORT_SECRET\nservice second /system/bin/second\nseclabel u:r:system_server:s0\n")
        val output = runReader(root)
        assertEquals(output, "ok", OemServiceMetadata.interpret(output, 1).status)
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
        assertEquals("incomplete_metadata", OemServiceMetadata.interpret(output, 1).status)
        assertTrue(OemServiceMetadata.interpret(output, 1).values.isEmpty())
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
        assertEquals("incomplete_metadata", OemServiceMetadata.interpret(output, 1).status)
    }

    @Test fun `line byte count total file and output limits cannot claim complete observations`() {
        for ((text, reason) in listOf("x".repeat(1025) to "line_byte_limit", "# empty\n".repeat(513) to "line_count_limit",
            "service continued /system/bin/exe \\\n    --private\n" to "continuation")) {
            val root = temporary.newFolder(); initFile(root, "bounded.rc", text)
            val output = runReader(root)
            assertTrue(output, output.contains(reason))
            assertEquals("incomplete_metadata", OemServiceMetadata.interpret(output, 1).status)
        }
        val root = temporary.newFolder(); initFile(root, "one.rc", "service one /system/bin/one\n")
        for ((transform, reason) in listOf<Pair<(String) -> String, String>>(
            ({ value: String -> value.replace("-gt 512", "-gt 0") }) to "file_count_limit",
            ({ value: String -> value.replace("262144-total", "0-total") }) to "page_byte_limit",
            ({ value: String -> value.replace("-gt 5888", "-gt 500") }) to "output_limit")) {
            assertNotEquals(OemServiceMetadata.script(1), transform(OemServiceMetadata.script(1)))
            val output = runReader(root, transform)
            assertTrue(output, output.contains(reason))
            assertNotEquals("ok", OemServiceMetadata.interpret(output, 1).status)
        }
        val scoped = completePage()
        assertEquals("unexpected_output", OemServiceMetadata.interpret(scoped.removeSuffix("END SCOPED_COMPLETE"), 1).status)
        assertEquals("unexpected_output", OemServiceMetadata.interpret("$scoped\n[output truncated]", 1).status)
    }

    @Test fun `32 exact ordinal pages read 512 slots once and never inspect the next entry`() {
        val root = temporary.newFolder()
        for (index in 0..512) initFile(root, "%03d.rc".format(index), "service slot$index /system/bin/item$index\n")
        val services = mutableListOf<Int>()
        for (page in 1..32) {
            val output = runReader(root, page = page)
            assertEquals(output, "ok", OemServiceMetadata.interpret(output, page).status)
            val pageServices = Regex(" slot([0-9]+) /system/bin/item").findAll(output).map { it.groupValues[1].toInt() }.toList()
            assertEquals((16 * (page - 1) until 16 * page).toList(), pageServices)
            assertTrue(output.toByteArray().size <= OemServiceMetadata.PAGE_OUTPUT_BYTES)
            services.addAll(pageServices)
        }
        assertEquals((0 until 512).toList(), services)
        assertEquals(512, OemServiceMetadata.PAGE_COUNT * OemServiceMetadata.PAGE_ENTRIES)
        assertEquals(8 * 1024 * 1024, OemServiceMetadata.PAGE_COUNT * OemServiceMetadata.PAGE_SOURCE_BYTES)
        assertEquals(192 * 1024, OemServiceMetadata.PAGE_COUNT * OemServiceMetadata.PAGE_OUTPUT_BYTES)
    }

    @Test fun `selection precedes every external file utility and rejected links occupy page boundary slots`() {
        val root = temporary.newFolder()
        for (index in 0 until 35) initFile(root, "%03d.rc".format(index), "service slot$index /system/bin/item$index\n")
        val private = temporary.newFile().apply { writeText("service PRIVATE /private --PRIVATE\n") }
        val linked = File(root, "system/etc/init/015.rc").apply { delete() }
        Files.createSymbolicLink(linked.toPath(), private.toPath())
        val tools = temporary.newFolder()
        val log = temporary.newFile()
        for (tool in listOf("head", "stat", "readlink")) {
            File(tools, tool).apply {
                writeText("#!/bin/sh\nprintf '%s\\n' \"${'$'}@\" >> \"${'$'}OEM_UTILITY_LOG\"\nexec /usr/bin/$tool \"${'$'}@\"\n")
                setExecutable(true)
            }
        }
        val environment = mapOf("PATH" to "${tools.path}:${System.getenv("PATH")}", "OEM_UTILITY_LOG" to log.path)
        val first = runReader(root, environment = environment)
        assertTrue(first.contains("symlink_file ${linked.path}"))
        assertFalse(first.contains("slot16")); assertFalse(first.contains("PRIVATE"))
        assertEquals("incomplete_metadata", OemServiceMetadata.interpret(first, 1).status)
        log.writeText("")
        val second = runReader(root, page = 2, environment = environment)
        assertEquals("ok", OemServiceMetadata.interpret(second, 2).status)
        assertTrue(second.contains("slot16")); assertTrue(second.contains("slot31"))
        assertFalse(second.contains("slot15")); assertFalse(second.contains("slot32"))
        val observedFiles = log.readLines().filter { it.endsWith(".rc") }.toSet()
        assertEquals((16..31).map { File(root, "system/etc/init/%03d.rc".format(it)).path }.toSet(), observedFiles)
    }

    @Test fun `real source and filtered output budgets include retained framing and stop with omissions`() {
        val root = temporary.newFolder()
        val content = ("#" + "x".repeat(510) + "\n").repeat(128)
        assertEquals(65536, content.length)
        for (index in 0..4) initFile(root, "$index.rc", content)
        val tools = temporary.newFolder()
        val log = temporary.newFile()
        File(tools, "head").apply {
            writeText("#!/bin/sh\nprintf '%s\\n' \"${'$'}2\" >> \"${'$'}OEM_HEAD_LOG\"\nexec /usr/bin/head \"${'$'}@\"\n")
            setExecutable(true)
        }
        val output = runReader(root, environment = mapOf("PATH" to "${tools.path}:${System.getenv("PATH")}", "OEM_HEAD_LOG" to log.path))
        assertTrue(output.contains("INCOMPLETE page_byte_limit"))
        assertTrue(output.contains("source_bytes=262144"))
        assertEquals(262144, log.readLines().sumOf { it.toInt() })
        assertEquals("incomplete_metadata", OemServiceMetadata.interpret(output, 1).status)
        val outputRoot = temporary.newFolder()
        initFile(outputRoot, "many.rc", (0..100).joinToString("\n") { "service item$it /system/bin/item$it" })
        val capped = runReader(outputRoot)
        assertTrue(capped.contains("INCOMPLETE output_limit"))
        assertTrue(capped.toByteArray().size <= 6144)
        assertTrue(capped.contains("PAGE_END index=1 count=32 first=1 last=16\nEND INCOMPLETE"))
        assertEquals("incomplete_metadata", OemServiceMetadata.interpret(capped, 1).status)
    }

    private fun clock(values: List<String>): (String) -> String = { script ->
        val cases = values.mapIndexed { index, value -> "${index + 1}) now='$value';;" }.joinToString("\n")
        script.replace("previous=0", "previous=0\nclock_index=0")
            .replace("now=${'$'}(date +%s 2>/dev/null)", "clock_index=${'$'}((clock_index+1))\ncase ${'$'}clock_index in\n$cases\n*) now='${values.last()}';;\nesac")
    }

    @Test fun `bad clocks and rollback above start fail closed before source reads`() {
        for (value in listOf("", "no-clock", "-1", "01792240000", "9999999999999999999999999999", "2147483601")) {
            val root = temporary.newFolder(); initFile(root, "private.rc", "service MUST_NOT_READ /private\n")
            val output = runReader(root, clock(listOf(value)))
            assertTrue(output, output.contains("INCOMPLETE clock_unavailable"))
            assertFalse(output.contains("MUST_NOT_READ"))
            assertEquals("incomplete_metadata", OemServiceMetadata.interpret(output, 1).status)
        }
        val root = temporary.newFolder(); initFile(root, "private.rc", "service MUST_NOT_READ /private\n")
        val rollback = runReader(root, clock(listOf("1792240000", "1792240005", "1792240004")))
        assertTrue(rollback.contains("INCOMPLETE clock_rollback"))
        assertFalse(rollback.contains("MUST_NOT_READ"))
        assertEquals("incomplete_metadata", OemServiceMetadata.interpret(rollback, 1).status)
    }

    @Test fun `deadline after final file retains useful metadata and never claims page completion`() {
        val root = temporary.newFolder()
        for (index in 0 until 16) initFile(root, "%02d.rc".format(index), "service slot$index /system/bin/item$index\n")
        val values = List(49) { "1792240000" } + "1792240012"
        val output = runReader(root, clock(values))
        assertEquals(16, Regex("\nSERVICE ").findAll(output).count())
        assertTrue(output, output.contains("INCOMPLETE time_budget"))
        assertTrue(output.endsWith("END INCOMPLETE"))
        assertEquals("incomplete_metadata", OemServiceMetadata.interpret(output, 1).status)
    }

    @Test fun `page interpretation rejects another window malformed framing and excessive declared totals`() {
        val page = completePage(2)
        assertEquals("ok", OemServiceMetadata.interpret(page, 2).status)
        for (value in listOf(page, page.replace("PAGE_END index=2", "PAGE_END index=1"),
            page.replace("source_bytes=0", "source_bytes=262145"), page.replace("selected=0", "selected=17"),
            page.replace("END SCOPED_COMPLETE", "END UNKNOWN"), "$page\nUNRECOGNIZED")) {
            val expectedPage = if (value == page) 1 else 2
            assertEquals(value, "unexpected_output", OemServiceMetadata.interpret(value, expectedPage).status)
        }
    }

    @Test fun `normal page timeout preserves neighboring replies but fatal transport and cancellation stop later reads`() {
        for (mode in listOf("timeout", "fatal", "cancel")) {
            val directory = temporary.newFolder()
            var cancelled = false
            var calls = 0
            val result = DiLinkContextExport(DiLinkContextExport.Device { command ->
                calls++
                if (command.kind == DiLinkContextPlan.Kind.INIT_METADATA && command.subject == "2") {
                    when (mode) {
                        "timeout" -> DiLinkContextTransport.Reply(-1, "ERR timeout 30s")
                        "fatal" -> DiLinkContextTransport.Reply(null, "", "Transport did not return; call remains pending, no retry", true)
                        else -> { cancelled = true; DiLinkContextTransport.Reply(0, reply(command)) }
                    }
                } else DiLinkContextTransport.Reply(0, reply(command))
            }, directory, { cancelled }, {}, ample, profile = profile).run()
            assertEquals(if (mode == "timeout") 54 else 24, calls)
            assertEquals(if (mode == "cancel") "cancelled" else "partial", result.outcome)
            ZipFile(result.archives.single()).use { zip ->
                assertTrue(zip.getInputStream(zip.getEntry("23-init_service_metadata_01.combined.txt")).reader().readText().contains("PAGE index=1"))
                val rows = JSONObject(zip.getInputStream(zip.getEntry("resultats.json")).reader().readText()).getJSONArray("commands")
                assertEquals(if (mode == "timeout") "ok" else "not_attempted", rows.getJSONObject(24).getString("status"))
                assertEquals(if (mode == "timeout") "timeout" else if (mode == "fatal") "transport_error" else "ok", rows.getJSONObject(23).getString("status"))
            }
        }
    }

    @Test fun `V1 archive recovery preserves original profile bytes and sharing never relabels as V2`() {
        val directory = temporary.newFolder("oem-service-20261017-131425-000")
        val archive = File(directory, "oem-service-001.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("plan.json"))
            zip.write("""{"profile":"OEM_STRATEGY_SERVICE_METADATA_V1","planned":23,"app_runtime":{"app_version_name":"1.9.41-beta","app_version_code":680}}""".toByteArray())
            zip.closeEntry()
        }
        File(directory, "RESULT.txt").writeText("Identité du service OEM : partial\nProfil : OEM_STRATEGY_SERVICE_METADATA_V1\n23/23 lectures traitées.\n")
        val before = sha(archive.readBytes())
        val state = FullApkExportSession.recoverPrevious(ExportKind.OEM_SERVICE, directory)
        assertEquals("previous", state.outcome)
        assertFalse(state.countsKnown)
        assertTrue(state.stage.contains("OEM_STRATEGY_SERVICE_METADATA_V1"))
        val controller = Robolectric.buildActivity(DiagActivity::class.java).create()
        try {
            val method = DiagActivity::class.java.getDeclaredMethod("showFullExportFiles", FullApkExport.Progress::class.java).apply { isAccessible = true }
            method.invoke(controller.get(), state)
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val message = org.robolectric.Shadows.shadowOf(dialog).message.toString()
            assertTrue(message.contains("collecte d’origine"))
            assertFalse(message.contains("54 lectures")); assertFalse(message.contains(profile.id))
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().listView.adapter.getItem(0).toString().startsWith(archive.name))
        } finally { controller.destroy() }
        assertEquals(before, sha(archive.readBytes()))
        ZipFile(archive).use { zip -> assertTrue(zip.getInputStream(zip.getEntry("plan.json")).reader().readText().contains("OEM_STRATEGY_SERVICE_METADATA_V1")) }
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
        assertEquals(53, result.completed)
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

    @Test fun `one OEM entry offers distinct metadata and source profiles and cancelling service confirmation launches nothing`() {
        val controller = Robolectric.buildActivity(DiagActivity::class.java).create()
        try {
            val buttons = mutableListOf<Button>()
            fun visit(view: View) { if (view is Button) buttons.add(view); if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i)) }
            visit(controller.get().window.decorView)
            assertEquals(1, buttons.count { it.text.toString() == "Relevés locaux OEM" })
            buttons.single { it.text.toString() == "Relevés locaux OEM" }.performClick()
            val list = ShadowAlertDialog.getLatestAlertDialog().listView
            assertEquals(6, list.adapter.count)
            assertTrue(list.adapter.getItem(0).toString().contains("16 lectures"))
            assertTrue(list.adapter.getItem(1).toString().contains("54 lectures"))
            assertEquals("Contexte cluster — bibliothèque et processus", list.adapter.getItem(4).toString())
            assertEquals("Relevés précédents", list.adapter.getItem(5).toString())
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
            assertTrue(org.robolectric.Shadows.shadowOf(files).message.toString().contains("collecte d’origine"))
            assertFalse(org.robolectric.Shadows.shadowOf(files).message.toString().contains(profile.id))
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
