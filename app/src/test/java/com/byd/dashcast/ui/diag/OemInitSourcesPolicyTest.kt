package com.byd.dashcast.ui.diag

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

class OemInitSourcesPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun run(root: File, index: Int, offset: Int = 0): OemInitSourcesPolicy.Page {
        val literal = OemInitSourcesPolicy.roots[index]
        val actual = if (index == 0) root.path else root.path + literal
        val script = OemInitSourcesPolicy.script(index, offset)
            .replace("d=" + ApkInventoryShell.quote(literal), "d=" + ApkInventoryShell.quote(actual))
            .replace("/init*.rc", "${root.path}/init*.rc")
        val output = temporary.newFile()
        val process = ProcessBuilder("/bin/sh", "-c", "sh -c " + ApkInventoryShell.quote(script))
            .redirectErrorStream(true).redirectOutput(output).start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals(output.readText(), 0, process.exitValue())
        val normalized = output.readText().lineSequence().map { line ->
            if (!line.startsWith("ENTRY|")) line else {
                val f = line.split('|').toMutableList()
                val path = String(Base64.getDecoder().decode(f[2]), Charsets.UTF_8).removePrefix(root.path)
                f[2] = Base64.getEncoder().encodeToString(path.toByteArray(Charsets.UTF_8)); f.joinToString("|")
            }
        }.joinToString("\n")
        return OemInitSourcesPolicy.parse(index, offset, normalized)
    }
    @Test fun `real shell inventories hostile UTF8 names exactly without executing file text`() {
        val root = temporary.newFolder(); val dir = File(root, "system/etc/init").apply { mkdirs() }
        val marker = File(root, "NEVER_EXECUTED")
        val names = listOf("android.hardware.foo@1.0-service.rc", "with space.rc", "quote'\".rc", "é漢字.rc", "line\nbreak.rc", "\$(echo NEVER).rc")
        for (name in names) File(dir, name).writeText("on boot\n exec -- /bin/touch ${marker.path}\nservice \"quoted\" /vendor/bin/svc --secret\n")
        val page = run(root, 1)
        assertEquals("ok", page.rootStatus); assertFalse(page.more)
        assertEquals(names.map { "/system/etc/init/$it" }.toSet(), page.entries.map { it.path }.toSet())
        assertTrue(page.entries.all { it.status == "ok" && it.stamp!!.size > 0 })
        assertFalse(marker.exists())
    }
    @Test fun `pages account for rejected links FIFO and oversized regular source without reading it`() {
        val root = temporary.newFolder(); val dir = File(root, "vendor/etc/init").apply { mkdirs() }
        repeat(19) { File(dir, "file-${it.toString().padStart(2, '0')}.rc").writeText("# fixture") }
        File(dir, "big.rc").writeBytes(ByteArray(65537) { 65 })
        Files.createSymbolicLink(File(dir, "linked.rc").toPath(), temporary.newFile().toPath())
        val fifo = File(dir, "fifo.rc"); assertEquals(0, ProcessBuilder("mkfifo", fifo.path).start().waitFor())
        val first = run(root, 3); val second = run(root, 3, 16)
        assertEquals(16, first.entries.size); assertTrue(first.more); assertFalse(second.more)
        val entries = first.entries + second.entries
        assertEquals(22, entries.size)
        assertEquals("symlink_file", entries.single { it.path!!.endsWith("linked.rc") }.status)
        assertEquals("nonregular_file", entries.single { it.path!!.endsWith("fifo.rc") }.status)
        assertEquals(65537L, entries.single { it.path!!.endsWith("big.rc") }.stamp!!.size)
    }
    @Test fun `unavailable or aliased roots never become empty complete source inventories`() {
        val root = temporary.newFolder()
        assertEquals("root_unavailable", run(root, 1).rootStatus)
        File(root, "system/etc").mkdirs()
        Files.createSymbolicLink(File(root, "system/etc/init").toPath(), temporary.newFolder().toPath())
        assertEquals("symlink_root", run(root, 1).rootStatus)
        File(root, "init.target.rc").writeText("import /PRIVATE/secret.rc\n")
        val page = run(root, 0)
        assertEquals(listOf("/init.target.rc"), page.entries.map { it.path })
    }
    @Test fun `parser rejects missing trailers malformed encoding scope escapes and excessive fields`() {
        val root = temporary.newFolder(); File(root, "init.rc").writeText("# init")
        val page = run(root, 0)
        val entry = page.entries.single()
        val valid = "BEGIN|OEM_INIT_SOURCES_V1|0|0\nROOT|ok|41ed:4096:1:1:2:3\nENTRY|1|${entry.encodedPath}|ok|81a4:6:1:1:4:3\nEND|0|0|1|0"
        assertEquals(1, OemInitSourcesPolicy.parse(0, 0, valid).entries.size)
        val escape = Base64.getEncoder().encodeToString("/data/private.rc".toByteArray())
        for (bad in listOf(valid.substringBeforeLast('\n'), valid + "\nEXTRA", valid.replace(entry.encodedPath, "!!!"),
            valid.replace(entry.encodedPath, escape), valid.replace("END|0|0|1|0", "END|0|0|1|1"), valid.replace("41ed:", "81a4:"))) {
            try { OemInitSourcesPolicy.parse(0, 0, bad); fail("Accepted malformed inventory") } catch (_: java.io.IOException) { }
        }
        val invalidUtf8 = Base64.getEncoder().encodeToString(byteArrayOf(47, 105, 110, 105, 116, -1, 46, 114, 99))
        val invalid = OemInitSourcesPolicy.parse(0, 0, valid.replace(entry.encodedPath, invalidUtf8)).entries.single()
        assertNull(invalid.path); assertEquals(invalidUtf8, invalid.encodedPath); assertEquals("invalid_utf8_path", invalid.status)
    }
}
