package com.byd.dashcast.ui.diag

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

class FullApkExportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val ampleSpace = { _: File -> Long.MAX_VALUE }
    private fun source(path: String, size: Int?) = FullApkExport.Source(path, size?.toLong(), linkedSetOf("test:$path"))
    private fun device(sources: List<FullApkExport.Source>, open: (FullApkExport.Source) -> InputStream) =
        object : FullApkExport.Device {
            override fun discover(checkCancelled: () -> Unit, update: (String, Int) -> Unit): FullApkExport.Inventory {
                update("test discovery", sources.size)
                return FullApkExport.Inventory(sources, listOf("protected roots: access denied"), mapOf("model" to "test firmware"))
            }
            override fun open(source: FullApkExport.Source) = open(source)
        }

    @Test fun `standalone volumes contain exact bytes hashes and distinct paths for identical names`() {
        val sources = listOf(source("/system/app/oem/base.apk", 6), source("/data/app/oem/base.apk", 6), source("/data/app/oem/split_fr.apk", 6))
        val data = ByteArray(6) { (it + 10).toByte() }
        val directory = temporary.newFolder()
        val result = FullApkExport(device(sources) { ByteArrayInputStream(data) }, directory, { false }, {}, 10, ampleSpace).run()
        assertEquals("finished", result.outcome)
        assertEquals(3, result.archives.size)
        val entries = mutableSetOf<String>()
        for (archive in result.archives) ZipFile(archive).use { zip ->
            val apk = zip.entries().toList().single { it.name.startsWith("apks/") }
            assertTrue(entries.add(apk.name))
            assertArrayEquals(data, zip.getInputStream(apk).readBytes())
            val manifest = zip.getInputStream(zip.getEntry("manifest.json")).reader().readText()
            assertTrue(manifest.contains("protected roots: access denied"))
            assertTrue(manifest.contains("unverified"))
            val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertTrue(manifest.contains(hash))
        }
        assertFalse(directory.listFiles()!!.any { it.name.endsWith(".part") })
        assertTrue(File(directory, "manifest.json").readText().contains("split_fr.apk"))
    }

    @Test fun `oversized APK occupies its own usable volume without being dropped`() {
        val sources = listOf(source("/one.apk", 2), source("/large.apk", 20), source("/last.apk", 2))
        val result = FullApkExport(device(sources) { ByteArrayInputStream(ByteArray(it.size!!.toInt())) },
            temporary.newFolder(), { false }, {}, 10, ampleSpace).run()
        assertEquals("finished", result.outcome)
        assertEquals(3, result.archives.size)
        result.archives.forEach { file -> ZipFile(file).use { assertEquals(2, it.size()) } }
    }

    @Test fun `mid-file source failure preserves healthy APKs in current and earlier volumes`() {
        val sources = listOf(source("/good1.apk", 10), source("/good2.apk", 3), source("/broken.apk", 3), source("/good3.apk", 4))
        val directory = temporary.newFolder()
        val result = FullApkExport(device(sources) {
            if (it.path == "/broken.apk") object : InputStream() {
                var readOnce = false
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (readOnce) throw IOException("simulated transport failure")
                    readOnce = true; b[off] = 7; return 1
                }
            } else ByteArrayInputStream(ByteArray(it.size!!.toInt()))
        }, directory, { false }, {}, 10, ampleSpace).run()
        assertEquals("partial", result.outcome)
        assertEquals(3, result.completed)
        assertEquals(1, result.failed)
        assertEquals(2, result.archives.size)
        assertEquals(0, directory.listFiles()!!.count { it.name.endsWith(".part") })
        result.archives.forEach { ZipFile(it).use { z -> assertNotNull(z.getEntry("manifest.json")) } }
        val manifest = File(directory, "manifest.json").readText()
        assertTrue(manifest.contains("entry is incomplete, do not use as an APK"))
        assertTrue(manifest.contains("simulated transport failure"))
        ZipFile(result.archives.last()).use { zip ->
            val healthy = zip.entries().toList().filter { it.name.endsWith("good2.apk") || it.name.endsWith("good3.apk") }
            assertEquals(2, healthy.size)
            healthy.forEach { assertTrue(zip.getInputStream(it).readBytes().isNotEmpty()) }
        }
    }

    @Test fun `cancellation during large file retains closed ZIP and never publishes current part`() {
        val sources = listOf(source("/finished.apk", 4), source("/cancelled.apk", FullApkExport.BUFFER_BYTES * 2))
        var cancel = false
        val directory = temporary.newFolder()
        val result = FullApkExport(device(sources) {
            if (it.path == "/cancelled.apk") object : ByteArrayInputStream(ByteArray(it.size!!.toInt())) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    cancel = true
                    return n
                }
            } else ByteArrayInputStream(ByteArray(4))
        }, directory, { cancel }, {}, 4, ampleSpace).run()
        assertEquals("cancelled", result.outcome)
        assertEquals(1, result.archives.size)
        assertEquals(1, result.completed)
        assertEquals(1, result.failed)
        assertTrue(File(directory, "apks-002.zip.part").exists())
        assertFalse(File(directory, "apks-002.zip").exists())
        assertTrue(File(directory, "manifest.json").readText().contains("archive_incomplete"))
    }

    @Test fun `empty and short source are failures rather than successful EOF`() {
        val sources = listOf(source("/denied.apk", null), source("/short.apk", 4))
        val directory = temporary.newFolder()
        val result = FullApkExport(device(sources) { ByteArrayInputStream(if (it.path == "/short.apk") byteArrayOf(1) else byteArrayOf()) },
            directory, { false }, {}, 10, ampleSpace).run()
        assertEquals("partial", result.outcome)
        assertTrue(result.archives.isNotEmpty()) // valid ZIP, failed entries explicitly marked
        assertEquals(0, result.completed)
        assertEquals(2, result.failed)
    }

    @Test fun `running out of storage preserves completed archives and marks remaining sources`() {
        val sources = listOf(source("/one.apk", 4), source("/two.apk", 4), source("/three.apk", 4))
        var free = Long.MAX_VALUE
        val directory = temporary.newFolder()
        val result = FullApkExport(device(sources) {
            if (it.path == "/two.apk") free = 1
            ByteArrayInputStream(ByteArray(4))
        }, directory, { false }, {}, 4, { free }).run()
        assertEquals("failed", result.outcome)
        assertEquals(1, result.archives.size)
        assertEquals(1, result.completed)
        assertTrue(File(directory, "manifest.json").readText().contains("not_attempted"))
        ZipFile(result.archives.single()).use { assertNotNull(it.getEntry("manifest.json")) }
    }

    @Test fun `read progress continues inside a single large file`() {
        val source = source("/large.apk", FullApkExport.BUFFER_BYTES * 3)
        val seen = mutableListOf<FullApkExport.Progress>()
        val input = object : ByteArrayInputStream(ByteArray(source.size!!.toInt())) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { Thread.sleep(260); return super.read(b, off, len) }
        }
        val result = FullApkExport(device(listOf(source)) { input }, temporary.newFolder(), { false }, { seen.add(it) }, freeBytes = ampleSpace).run()
        assertEquals("finished", result.outcome)
        assertTrue(seen.any { it.bytes > 0 && it.bytes < source.size!! && it.completed == 0 })
    }

    @Test fun `manifest escapes path control characters`() {
        assertEquals("\"a\\\"b\\\\c\\n\\t\\u0001\"", FullApkExport.json("a\"b\\c\n\t\u0001"))
    }

    @Test fun `discovery failure is partial even when every discovered APK exported`() {
        val apk = source("/visible.apk", 1)
        val device = object : FullApkExport.Device {
            override fun discover(checkCancelled: () -> Unit, update: (String, Int) -> Unit) =
                FullApkExport.Inventory(listOf(apk), listOf("known roots protected"), emptyMap(), listOf("shell scan timed out"))
            override fun open(source: FullApkExport.Source) = ByteArrayInputStream(byteArrayOf(7))
        }
        val directory = temporary.newFolder()
        val result = FullApkExport(device, directory, { false }, {}, freeBytes = ampleSpace).run()
        assertEquals("partial", result.outcome)
        assertEquals(1, result.completed)
        assertTrue(File(directory, "manifest.json").readText().contains("shell scan timed out"))
    }

    @Test fun `empty discovery never claims successful complete export`() {
        val result = FullApkExport(device(emptyList()) { error("no sources") }, temporary.newFolder(), { false }, {}, freeBytes = ampleSpace).run()
        assertEquals("partial", result.outcome)
        assertTrue(result.archives.isEmpty())
    }
}
