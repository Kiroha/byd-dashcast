package com.byd.dashcast.ui.diag

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.AccessDeniedException
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile

class SystemComplementExportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val profile = SystemExportProfile.DILINK_P15
    private val target = "/system/bin/fission_service"
    private val ample = { _: File -> Long.MAX_VALUE }

    @Test fun `complement probes only the fixed plan independently under both identities`() {
        val shell = mutableListOf<String>()
        val app = mutableListOf<String>()
        val denied = mutableListOf<String>()
        profile.discoverTargets(true, {}, { path, _ ->
            shell.add(path); denied.add("shell uid 2000: Permission denied: $path")
        }, { path -> app.add(path) })
        assertEquals(15, profile.requestedPaths.size)
        assertEquals(profile.requestedPaths, shell)
        assertEquals(shell, app)
        assertEquals(15, denied.size)
        assertTrue(profile.requestedPaths.all { it.startsWith("/system/bin/") || it.startsWith("/system/lib/") || it.startsWith("/system/lib64/") })
        assertFalse(profile.requestedPaths.any { it.contains('*') || it.endsWith(".rc") || it.endsWith(".xml") || it.endsWith(".jar") })
        for (path in profile.requestedPaths) {
            val command = SystemInventoryCommands.scan(path, "exact", profile = profile)
            assertFalse(command.contains("find "))
            assertFalse(command.contains("pm "))
            assertFalse(command.contains("head "))
            assertFalse(command.contains("getprop "))
        }
    }

    @Test fun `unavailable shell still probes app while cancellation prevents later identities`() {
        val app = mutableListOf<String>()
        profile.discoverTargets(false, {}, { _, _ -> fail("No shell probe without snapshots") }, app::add)
        assertEquals(profile.requestedPaths, app)
        var cancelled = false
        var shells = 0
        var apps = 0
        try {
            profile.discoverTargets(true, { if (cancelled) throw CancellationException() },
                { _, _ -> shells++; cancelled = true }, { apps++ })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(1, shells)
        assertEquals(0, apps)
    }

    private fun access(canonical: String = target, regular: Boolean = true, size: Long = 8,
                       denied: Boolean = false, readable: Boolean = true,
                       onAttributes: () -> Unit = {}) = object : SystemAppFileProbe.Access {
        override fun canonical(path: String) = canonical
        override fun attributes(canonical: String): SystemAppFileProbe.Attributes {
            onAttributes()
            if (denied) throw AccessDeniedException(canonical)
            return SystemAppFileProbe.Attributes(regular, size)
        }
        override fun readable(path: String) = readable
    }

    @Test fun `app metadata distinguishes denied unknown empty nonregular and valid positive sources`() {
        for (scope in listOf(profile, SystemExportProfile.FULL)) {
            try {
                SystemAppFileProbe.inspect(target, scope, access(denied = true))
                fail("Denied metadata is not an empty source")
            } catch (failure: AccessDeniedException) { assertEquals(target, failure.file) }
            for (invalid in listOf(access(size = 0), access(size = -1), access(regular = false), access(readable = false))) {
                try { SystemAppFileProbe.inspect(target, scope, invalid); fail("Invalid candidate accepted") }
                catch (_: java.io.IOException) { }
            }
            assertEquals(SystemAppFileProbe.Result(target, 8), SystemAppFileProbe.inspect(target, scope, access()))
        }
    }

    @Test fun `escaping aliases and out of scope paths fail before attribute or content reads`() {
        for (canonical in listOf("/data/private/fission_service", "/dev/null", "/proc/version", "/system/bin/unlisted")) {
            assertFalse(profile.accepted(target, canonical))
            try {
                SystemAppFileProbe.inspect(target, profile, access(canonical = canonical, onAttributes = { fail("No stat of escaping target") }))
                fail("Escaping target accepted")
            } catch (_: java.io.IOException) { }
        }
        assertFalse(profile.allowed("/system/bin/../bin/fission_service"))
        assertFalse(profile.allowed("/system/framework/framework.jar"))
        assertFalse(profile.allowed("/system/lib64/libbinder.so"))
        assertFalse(profile.allowed("/system/bin/fission_service\nother"))
    }

    @Test fun `shell distinguishes stat denial zero and special files without reading contents`() {
        val bin = temporary.newFolder("tools")
        fun tool(name: String, body: String) = File(bin, name).apply {
            writeText("#!/bin/sh\n$body\n"); assertTrue(setExecutable(true))
        }
        tool("readlink", "printf '%s\\n' ${ApkInventoryShell.quote(target)}")
        val stat = tool("stat", "printf 'Permission denied\\n' >&2; exit 1")
        fun run(regular: Boolean, scope: SystemExportProfile): String {
            // The fixture models type metadata only; it never creates files below /system.
            val prefix = "[() { if builtin test \"\$1\" = -f; then return ${if (regular) 0 else 1}; else builtin [ \"\$@\"; fi; }; "
            val inspect = "set -- ${ApkInventoryShell.quote(target)}; " + SystemInventoryCommands.inspectFiles("exact", scope)
            val builder = ProcessBuilder("bash", "-c", prefix + inspect).redirectErrorStream(true)
            builder.environment()["PATH"] = bin.path + ":" + builder.environment()["PATH"]
            val process = builder.start()
            return process.inputStream.bufferedReader().readText().also { assertEquals(0, process.waitFor()) }
        }
        for (scope in listOf(profile, SystemExportProfile.FULL)) {
            stat.writeText("#!/bin/sh\nprintf 'Permission denied\\n' >&2; exit 1\n")
            val denied = run(true, scope)
            assertTrue(denied.contains("canonical/stat unavailable"))
            assertFalse(denied.contains("regular file is empty"))
            assertFalse(denied.contains("SYS|"))
            stat.writeText("#!/bin/sh\nprintf '0\\n'\n")
            val empty = run(true, scope)
            assertTrue(empty.contains("regular file is empty"))
            assertFalse(empty.contains("SYS|"))
            stat.writeText("#!/bin/sh\nprintf '8\\n'\n")
            val special = run(false, scope)
            assertTrue(special.contains("not a regular file"))
            assertFalse(special.contains("SYS|"))
            assertTrue(run(true, scope).contains("SYS|8|$target|$target"))
        }
    }

    @Test fun `shell copy recheck rejects changed canonical type or size before any payload read`() {
        val source = FullApkExport.Source(target, 8, canonicalPath = target)
        val bin = temporary.newFolder("recheck-tools")
        val readlink = File(bin, "readlink").apply { assertTrue(createNewFile()); assertTrue(setExecutable(true)) }
        val stat = File(bin, "stat").apply { writeText("#!/bin/sh\nprintf '8\\n'\n"); assertTrue(setExecutable(true)) }
        fun run(canonical: String = target, regular: Boolean = true, size: String = "8"): Pair<Int, String> {
            readlink.writeText("#!/bin/sh\nprintf '%s\\n' ${ApkInventoryShell.quote(canonical)}\n")
            stat.writeText("#!/bin/sh\nprintf '%s\\n' ${ApkInventoryShell.quote(size)}\n")
            val prefix = "[() { if builtin test \"\$1\" = -f; then return ${if (regular) 0 else 1}; else builtin [ \"\$@\"; fi; }; "
            val builder = ProcessBuilder("bash", "-c", prefix + SystemInventoryCommands.recheck(source, profile)).redirectErrorStream(true)
            builder.environment()["PATH"] = bin.path + ":" + builder.environment()["PATH"]
            val process = builder.start()
            val text = process.inputStream.bufferedReader().readText()
            return process.waitFor() to text
        }
        assertEquals(0 to "SYS|8|$target|$target\n", run())
        for (invalid in listOf(run(canonical = "/data/private/fission_service"), run(regular = false), run(size = "9"), run(size = "unknown"))) {
            assertNotEquals(0, invalid.first)
            assertFalse(invalid.second.contains("SYS|"))
        }
    }

    @Test fun `P15 manifest and ZIP retain profile requested paths independent identity issues and hashes`() {
        val directory = temporary.newFolder()
        val source = FullApkExport.Source(target, 3, linkedSetOf("app:uid=10082:exact:$target"), target)
        val device = object : FullApkExport.Device {
            override fun discover(checkCancelled: () -> Unit, update: (String, Int) -> Unit) = FullApkExport.Inventory(
                listOf(source), listOf("Fixed exact paths"), emptyMap(), listOf("shell:uid=2000:Permission denied:$target"),
                profile.id, profile.requestedPaths)
            override fun open(source: FullApkExport.Source) = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }
        val result = FullApkExport(device, directory, { false }, {}, freeBytes = ample, kind = ExportKind.SYSTEM_P15).run()
        assertEquals("partial", result.outcome)
        assertEquals(1, result.completed)
        assertEquals(ExportKind.SYSTEM_P15, result.kind)
        val manifest = File(directory, "manifest.json").readText()
        assertTrue(manifest.contains("\"scope\":\"dilink-p15-v1\""))
        assertTrue(manifest.contains("\"kind\":\"SYSTEM_P15\""))
        assertTrue(manifest.contains("app:uid=10082"))
        assertTrue(manifest.contains("shell:uid=2000:Permission denied"))
        profile.requestedPaths.forEach { assertTrue(manifest.contains(FullApkExport.json(it))) }
        assertTrue(manifest.contains("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"))
        ZipFile(result.archives.single()).use { zip ->
            assertNotNull(zip.getEntry("system/000000/fission_service"))
            assertNotNull(zip.getEntry("manifest-volume.json"))
            assertNull(zip.getEntry("manifest.json"))
        }
    }

    @Test fun `discovery cancellation still records fixed requested scope and no finished payload`() {
        val directory = temporary.newFolder()
        val device = object : FullApkExport.Device {
            override fun discover(checkCancelled: () -> Unit, update: (String, Int) -> Unit): FullApkExport.Inventory = throw CancellationException()
            override fun open(source: FullApkExport.Source) = error("No payload")
        }
        val result = FullApkExport(device, directory, { false }, {}, freeBytes = ample, kind = ExportKind.SYSTEM_P15).run()
        assertEquals("cancelled", result.outcome)
        assertTrue(result.archives.isEmpty())
        val manifest = File(directory, "manifest.json").readText()
        assertTrue(manifest.contains(profile.id))
        profile.requestedPaths.forEach { assertTrue(manifest.contains(FullApkExport.json(it))) }
    }

    @Test fun `initial metadata failure remains partial even with a matching fingerprint and no runtime scan`() {
        val firmware = linkedMapOf("ro.build.fingerprint" to "same")
        val issues = mutableListOf<String>()
        SystemExportPolicy.recordContextIssue("PROP|ro.build.fingerprint|same\n__DC_SYSTEM_CONTEXT__:1", true, firmware, issues::add)
        assertTrue(issues.single().startsWith("Before context"))
        assertEquals(issues.single(), firmware["contextCaptureIssue"])
        val source = FullApkExport.Source(target, 1, canonicalPath = target)
        val device = object : FullApkExport.Device {
            override fun discover(checkCancelled: () -> Unit, update: (String, Int) -> Unit) = FullApkExport.Inventory(
                listOf(source), emptyList(), firmware, issues, profile.id, profile.requestedPaths)
            override fun open(source: FullApkExport.Source) = ByteArrayInputStream(byteArrayOf(1))
            override fun finish(cancelled: Boolean) = FullApkExport.ContextCheck(mapOf("ro.build.fingerprint" to "same"))
        }
        val directory = temporary.newFolder()
        val result = FullApkExport(device, directory, { false }, {}, freeBytes = ample, kind = ExportKind.SYSTEM_P15).run()
        assertEquals("partial", result.outcome)
        assertTrue(File(directory, "manifest.json").readText().contains("Before context"))
        val after = linkedMapOf<String, String>()
        SystemExportPolicy.recordContextIssue("__DC_SYSTEM_CONTEXT__:1", false, after) { fail("After errors use ContextCheck instead of discovery") }
        assertTrue(after.getValue("contextCaptureIssue").startsWith("After context"))
    }

    @Test fun `complement recovery has a distinct root and preserves profile without source reads`() {
        val base = temporary.newFolder(ExportKind.SYSTEM_P15.rootName)
        val folder = File(base, "${ExportKind.SYSTEM_P15.prefix}20261016-150000-001").apply { mkdir() }
        File(folder, "RUNNING.txt").writeText("interrupted")
        File(folder, "system-001.zip").writeText("closed fixture")
        val latest = ExportKind.latest(listOf(ExportKind.SYSTEM_P15 to base))!!
        val recovered = FullApkExportSession.recoverPrevious(latest.first, latest.second)
        assertEquals(ExportKind.SYSTEM_P15, recovered.kind)
        assertEquals(profile, recovered.kind.systemProfile)
        assertEquals("interrupted/partial", recovered.outcome)
        assertFalse(recovered.running)
        assertEquals("system-001.zip", recovered.archives.single().name)
    }
}
