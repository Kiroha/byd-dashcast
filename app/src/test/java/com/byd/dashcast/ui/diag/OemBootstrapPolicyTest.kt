package com.byd.dashcast.ui.diag

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

class OemBootstrapPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun shell(script: String): String {
        val output = temporary.newFile()
        val child = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).redirectOutput(output).start()
        assertTrue(child.waitFor(10, TimeUnit.SECONDS)); assertEquals(output.readText(), 0, child.exitValue())
        return output.readText()
    }
    private fun framed(label: String, limits: Map<String, Int>, body: (String, Int) -> Pair<ByteArray, Int>): String = buildString {
        append("BEGIN|${OemBootstrapPolicy.PROFILE}|$label\n")
        limits.forEach { (id, cap) ->
            val (bytes, exit) = body(id, cap)
            val encoded = Base64.getEncoder().encodeToString(bytes + "\n.DC_BOOT_RC.$exit\n".toByteArray())
            append("BLOB|$id|$cap|$encoded\n")
        }
        append("END|${OemBootstrapPolicy.PROFILE}|$label\n")
    }
    @Test fun `bounded real proc shell preserves NUL and non UTF8 and brackets stat without executing content`() {
        val root = temporary.newFolder(); val pid = 123
        val stat = statBytes(pid, "init", "91")
        File(root, "stat").writeBytes(stat)
        val raw = byteArrayOf(47, 105, 110, 105, 116, 0, -1, 0, 36, 40, 41)
        File(root, "cmdline").writeBytes(raw)
        File(root, "status").writeText("Name:\tinit\n")
        File(root, "mountinfo").writeBytes(ByteArray(16386) { 65 })
        File(root, "ns").mkdir()
        for (name in listOf("root", "exe", "ns/mnt", "ns/pid")) Files.createSymbolicLink(File(root, name).toPath(), File("/namespace/$name").toPath())
        val script = OemBootstrapPolicy.processScript(pid).replace("/proc/$pid/", "${root.path}/")
        val blobs = OemBootstrapPolicy.parse("pid_$pid", shell(script), OemBootstrapPolicy.processLimits)
        assertArrayEquals(raw, blobs.single { it.id == "cmdline" }.bytes)
        assertEquals("truncated", blobs.single { it.id == "mountinfo" }.status)
        assertEquals(16384, blobs.single { it.id == "mountinfo" }.bytes.size)
        assertEquals("starttime_bracket_consistent", OemBootstrapPolicy.processIdentity(OemBootstrapPolicy.Pid(pid, listOf("ps_exact_init")), blobs)["identity_status"])
        assertTrue(blobs.sumOf { it.bytes.size } < 64 * 1024)
    }
    @Test fun `actual proc size zero is read through bounded EXEC rather than strict regular source contract`() {
        val pid = ProcessHandle.current().pid().toInt()
        assertEquals(0, File("/proc/$pid/stat").length())
        val blobs = OemBootstrapPolicy.parse("pid_$pid", shell(OemBootstrapPolicy.processScript(pid)), OemBootstrapPolicy.processLimits)
        assertEquals("ok", blobs.single { it.id == "stat_before" }.status)
        assertNotNull(OemBootstrapPolicy.stat(blobs.single { it.id == "stat_before" }, pid))
        assertTrue(blobs.single { it.id == "cmdline" }.bytes.contains(0))
    }
    @Test fun `PID selection ignores homonyms deduplicates fixed PIDs and caps each exact name`() {
        val ps = " PID NAME\n1 init\n99 app_process\n2 init\n3 init\n4 init\n5 init\n6 init\n" +
            (100..105).joinToString("\n") { "$it strategyservice" } + "\n200 strategyservice-helper\n201 /system/bin/strategyservice\n202 init_worker\n"
        val selected = OemBootstrapPolicy.select(blob("ps", ps.toByteArray()), 99)
        assertEquals(listOf(1, 99, 2, 3, 4, 100, 101, 102, 103), selected.pids.map { it.pid })
        assertEquals(2, selected.issues.size)
        assertEquals(2, selected.pids.first().roles.size)
        assertTrue(selected.pids.size <= 10)
        val bad = OemBootstrapPolicy.select(blob("ps", "PID NAME\n10 init\n10 strategyservice\n".toByteArray()), 99)
        assertEquals(listOf(1, 99), bad.pids.map { it.pid }); assertFalse(bad.issues.isEmpty())
    }
    @Test fun `PID reuse disappearance and renamed exact name cannot attribute batch observations`() {
        val pid = OemBootstrapPolicy.Pid(123, listOf("ps_exact_strategyservice"))
        for (mode in listOf("reuse", "missing", "renamed", "bracket")) {
            val before = blob("stat_before", statBytes(123, "strategyservice", "11"))
            val after = when (mode) {
                "missing" -> blob("stat_after", byteArrayOf(), "not_found")
                "renamed" -> blob("stat_after", statBytes(123, "other", "11"))
                "reuse" -> blob("stat_after", statBytes(123, "strategyservice", "12"))
                else -> blob("stat_after", statBytes(123, "strategyservice", "11"))
            }
            val identity = OemBootstrapPolicy.processIdentity(pid, listOf(before, after))
            assertEquals(if (mode == "bracket") "starttime_bracket_consistent" else "unstable_or_unobserved", identity["identity_status"])
        }
        val odd = OemBootstrapPolicy.stat(blob("stat_before", statBytes(123, "a ) b\n(name)", "999")), 123)
        assertEquals("a ) b\n(name)", odd!!.comm); assertEquals("999", odd.starttime)
    }
    @Test fun `failure diagnostic remains distinct from absent and command prefix exhaustion remains explicit`() {
        val limits = linkedMapOf("denied" to 1024, "missing" to 1024, "long" to 4)
        val text = framed("fixture", limits) { id, _ -> when (id) {
            "denied" -> "head: /proc/1/cmdline: Permission denied\n".toByteArray() to 1
            "missing" -> "head: /proc/2/cmdline: No such file or directory\n".toByteArray() to 1
            else -> ByteArray(5) { 65 } to 0
        } }
        val blobs = OemBootstrapPolicy.parse("fixture", text, limits)
        assertEquals(listOf("access_denied", "not_found", "truncated"), blobs.map { it.status })
        val cut = "BEGIN|${OemBootstrapPolicy.PROFILE}|x\nBLOB|x|4|${Base64.getEncoder().encodeToString(ByteArray(132) { 65 })}\nEND|${OemBootstrapPolicy.PROFILE}|x"
        val truncated = OemBootstrapPolicy.parse("x", cut, mapOf("x" to 4)).single()
        assertEquals("truncated_exit_unobserved", truncated.status); assertNull(truncated.exit)
    }
    @Test fun `missing framing malformed Base64 extra rows and oversized blobs are terminal parse errors`() {
        val good = framed("x", mapOf("x" to 4)) { _, _ -> byteArrayOf(0, -1) to 0 }
        val bad = listOf(good.substringBeforeLast("END"), good + "EXTRA", good.replace("BLOB|x|4|", "BLOB|else|4|"),
            good.replace(Regex("BLOB\\|x\\|4\\|[^\\n]+"), "BLOB|x|4|!!!!"),
            good.replace(Regex("BLOB\\|x\\|4\\|[^\\n]+"), "BLOB|x|4|${Base64.getEncoder().encodeToString(ByteArray(133))}"))
        for (text in bad) try { OemBootstrapPolicy.parse("x", text, mapOf("x" to 4)); fail("Accepted malformed metadata") } catch (_: IOException) { }
    }
    @Test fun `closed fixed source metadata refuses links devices and noncanonical aliases without source execution`() {
        val root = temporary.newFolder()
        val files = OemBootstrapPolicy.sources.map { File(root, it.removePrefix("/")).apply { parentFile!!.mkdirs() } }
        files[0].writeText("on boot\n exec -- /bin/touch NEVER_EXECUTE\n")
        Files.createSymbolicLink(files[1].toPath(), files[0].toPath())
        assertEquals(0, ProcessBuilder("mkfifo", files[2].path).start().waitFor())
        var script = OemBootstrapPolicy.sourceScript(0)
        OemBootstrapPolicy.sources.forEachIndexed { i, path -> script = script.replace(ApkInventoryShell.quote(path), ApkInventoryShell.quote(files[i].path)) }
        val normalized = OemBootstrapPolicy.parse("sources_0", shell(script), OemBootstrapPolicy.sourceLimits(0)).map { b ->
            if (b.id.startsWith("canonical_") && b.status == "ok") b.copy(bytes = b.bytes.toString(Charsets.UTF_8).removePrefix(root.path).toByteArray()) else b
        }
        val sources = OemBootstrapPolicy.sourceMetadata(normalized, 0)
        assertEquals("ok", sources[0].status); assertEquals("symlink_refused", sources[1].status)
        assertEquals("nonregular_refused", sources[2].status); assertEquals("not_found", sources[3].status)
        val alias = normalized.map { if (it.id == "canonical_0") it.copy(bytes = "/other/init\n".toByteArray()) else it }
        assertEquals("noncanonical_refused", OemBootstrapPolicy.sourceMetadata(alias, 0)[0].status)
        assertFalse(File(root, "NEVER_EXECUTE").exists())
        assertTrue(OemBootstrapPolicy.sources.size <= 40)
        assertFalse(OemBootstrapPolicy.sources.any { it.startsWith("/proc/") })
    }
    companion object {
        internal fun blob(id: String, bytes: ByteArray, status: String = "ok") = OemBootstrapPolicy.Blob(id, 32768, status, if (status == "ok") 0 else 1, bytes)
        fun statBytes(pid: Int, comm: String, start: String) = ("$pid ($comm) " + (listOf("S") + List(18) { "0" } + start + List(5) { "0" }).joinToString(" ") + "\n").toByteArray(Charsets.ISO_8859_1)
    }
}
