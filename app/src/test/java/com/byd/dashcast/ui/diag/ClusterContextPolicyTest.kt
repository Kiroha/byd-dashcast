package com.byd.dashcast.ui.diag

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ClusterContextPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun blob(id: String, value: String, status: String = "ok") =
        OemBootstrapPolicy.Blob(id, 4096, status, if (status == "ok") 0 else 1, value.toByteArray())

    @Test fun `only numeric PIDs of exact names are selected with two per name and six total`() {
        val rows = buildString {
            append("PID NAME\n")
            ClusterContextPolicy.names.forEachIndexed { n, name ->
                for (i in 1..4) append("${100 * (n + 1) + i} $name\n")
                append("${100 * (n + 1) + 10} ${name}_helper\n")
            }
            append("999 /system/bin/qtandroidnative\n")
        }
        val selected = ClusterContextPolicy.select(blob("ps", rows))
        assertEquals(listOf(101, 102, 201, 202, 301, 302), selected.pids.map { it.pid })
        assertEquals(3, selected.issues.size)
        for (bad in listOf("PID NAME\n1 qtandroidnative\n1 surfaceflinger\n", "UID PID NAME\n", "PID NAME\n-1 qtandroidnative\n"))
            assertTrue(ClusterContextPolicy.select(blob("ps", bad)).pids.isEmpty())
        assertTrue(ClusterContextPolicy.select(blob("ps", "", "access_denied")).pids.isEmpty())
    }

    @Test fun `full argv0 handles native literal paths and truncated Java comm without suffix matching`() {
        for (name in ClusterContextPolicy.names) {
            val pid = ClusterContextPolicy.Pid(123, name)
            val before = OemBootstrapPolicy.Blob("stat_before", 4096, "ok", 0, OemBootstrapPolicyTest.statBytes(123, name.take(15), "77"))
            val after = before.copy(id = "stat_after")
            val candidates = if (name == "com.xdja.containerservice") listOf(name) else listOf(name, "/system/bin/$name")
            for (argv0 in candidates) {
                val result = ClusterContextPolicy.identity(pid, listOf(before, blob("cmdline_argv0", "$argv0\u0000"), after))
                assertEquals(argv0, "starttime_bracket_consistent_name_verified", result["identity_status"])
            }
            for (bad in listOf("/tmp/$name\u0000", "$name-helper\u0000", name, "$name\u0000secret\u0000")) {
                assertEquals("unstable_or_unobserved", ClusterContextPolicy.identity(pid,
                    listOf(before, blob("cmdline_argv0", bad), after))["identity_status"])
            }
            val reused = after.copy(bytes = OemBootstrapPolicyTest.statBytes(123, name.take(15), "78"))
            assertEquals("unstable_or_unobserved", ClusterContextPolicy.identity(pid,
                listOf(before, blob("cmdline_argv0", "$name\u0000"), reused))["identity_status"])
        }
    }

    @Test fun `real bounded shell stops cmdline at first NUL and truncates maps without following content`() {
        val root = temporary.newFolder()
        val pid = 123
        File(root, "stat").writeBytes(OemBootstrapPolicyTest.statBytes(pid, "com.xdja.contain", "77"))
        File(root, "cmdline").writeBytes("com.xdja.containerservice\u0000--secret=do-not-export\u0000".toByteArray())
        File(root, "status").writeText("Name:\tcom.xdja.contain\n")
        val never = temporary.newFile().apply { writeText("DO_NOT_READ_THIS_LINK_TARGET") }
        File(root, "maps").writeText((never.path + " $(touch NEVER_EXECUTE)\n").repeat(4096))
        File(root, "ns").mkdir()
        for (name in listOf("exe", "root", "ns/mnt", "ns/pid"))
            Files.createSymbolicLink(File(root, name).toPath(), never.toPath())
        val (script, limits) = ClusterContextPolicy.script("pid_$pid", pid)
        val captured = temporary.newFile()
        val process = ProcessBuilder("bash", "-c", script.replace("/proc/$pid/", "${root.path}/"))
            .redirectErrorStream(true).redirectOutput(captured).start()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(captured.readText(), 0, process.exitValue())
        val blobs = OemBootstrapPolicy.parse("pid_$pid", captured.readText(), limits, ClusterContextPolicy.PROFILE)
        assertArrayEquals("com.xdja.containerservice\u0000".toByteArray(), blobs.single { it.id == "cmdline_argv0" }.bytes)
        assertEquals("truncated", blobs.single { it.id == "maps" }.status)
        assertEquals(ClusterContextPolicy.MAP_BYTES, blobs.single { it.id == "maps" }.bytes.size)
        assertFalse(blobs.any { "--secret" in it.bytes.toString(Charsets.ISO_8859_1) })
        assertFalse(blobs.any { "DO_NOT_READ_THIS_LINK_TARGET" in it.bytes.toString(Charsets.ISO_8859_1) })
        assertEquals("starttime_bracket_consistent_name_verified", ClusterContextPolicy.identity(
            ClusterContextPolicy.Pid(pid, "com.xdja.containerservice"), blobs)["identity_status"])
        assertTrue(captured.length() < OemBootstrapPolicy.MAX_REPLY_CHARS)
        assertFalse(File("NEVER_EXECUTE").exists())
    }

    @Test fun `wire bounds for six maximal process replies fit unchanged transport and aggregate budget`() {
        fun reserve(limits: Map<String, Int>) = limits.entries.sumOf { (id, cap) -> ((cap + 130L) / 3 + 1) * 4 + id.length + 128 } + 256
        val process = reserve(ClusterContextPolicy.processLimits)
        assertTrue(process < OemBootstrapPolicy.MAX_REPLY_CHARS)
        val total = reserve(ClusterContextPolicy.inventoryLimits) + reserve(ClusterContextPolicy.propertyLimits) +
            2 * reserve(ClusterContextPolicy.sourceLimits) + 6 * process
        assertTrue("$total", total < OemBootstrapPolicy.MAX_METADATA_BYTES)
    }

    @Test fun `unknown labels malformed PIDs and binary aliases are refused by closed policy`() {
        for ((label, pid) in listOf("pid_1;id" to 1, "pid_01" to 1, "pid_0" to 0, "inventory" to null)) {
            try { ClusterContextPolicy.script(label, pid); fail(label) } catch (_: IllegalArgumentException) {}
        }
        val good = listOf(blob("stat_0", "81a4:3:1:1:100:1\n"), blob("canonical_0", ClusterContextPolicy.PROVIDER + "\n"))
        assertEquals("ok", ClusterContextPolicy.provider(good).status)
        assertEquals("symlink_refused", ClusterContextPolicy.provider(good.map {
            if (it.id == "stat_0") it.copy(bytes = "a1ff:3:1:1:100:1\n".toByteArray()) else it
        }).status)
        assertEquals("noncanonical_refused", ClusterContextPolicy.provider(good.map {
            if (it.id == "canonical_0") it.copy(bytes = "/tmp/provider.so\n".toByteArray()) else it
        }).status)
        val hash = "a".repeat(64)
        assertEquals(hash, ClusterContextPolicy.sha(listOf(blob("sha_0", "$hash  ${ClusterContextPolicy.PROVIDER}\n")), 0))
        assertNull(ClusterContextPolicy.sha(listOf(blob("sha_0", "$hash  /tmp/provider.so\n")), 0))
    }
}
