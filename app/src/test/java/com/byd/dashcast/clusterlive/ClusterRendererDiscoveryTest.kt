package com.byd.dashcast.clusterlive

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException

internal fun rendererInputStreams(open: (String) -> InputStream) = object : ClusterRendererDiscovery.Access<InputStream> {
    override fun open(pid: String) = open(pid)
    override fun read(fd: InputStream, buffer: ByteArray, count: Int) = fd.read(buffer, 0, count)
    override fun close(fd: InputStream) = fd.close()
}

class ClusterRendererDiscoveryTest {
    private fun scanEntries(
        entries: ((String) -> Unit) -> Unit,
        openCmdline: (String) -> InputStream,
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
    ) = ClusterRendererDiscovery.scan(entries, rendererInputStreams(openCmdline), clock = clock)
    private fun scan(vararg names: String, read: (String) -> ByteArray = { "other\u0000".toByteArray() }) =
        scanEntries({ visit -> names.forEach(visit) }, { ByteArrayInputStream(read(it)) })
    private fun refused(result: ClusterRendererDiscovery.Result): String {
        val failure = runCatching { result.requireSingle() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        return failure!!.message!!
    }

    @Test fun `bare and absolute basename retain the existing matching contract`() {
        for (name in listOf("qtandroidnative", "/system/bin/qtandroidnative", "/another/path/qtandroidnative")) {
            val result = scan("12") { "$name\u0000--private-argument".toByteArray() }
            assertEquals("12", result.requireSingle())
            assertFalse(result.summary().contains("--private-argument"))
        }
    }

    @Test fun `arguments suffixes and nonnumeric proc entries never select a renderer`() {
        val result = scan("self", "0", "-1", "1234567890", "10", "11", "12") {
            when (it) {
                "10" -> "other\u0000qtandroidnative\u0000SECRET".toByteArray()
                "11" -> "qtandroidnative-extra\u0000".toByteArray()
                "12" -> "prefix-qtandroidnative\u0000".toByteArray()
                else -> error("Non-PID must not be read")
            }
        }
        assertEquals(3, result.pids); assertEquals(3, result.readable)
        assertTrue(refused(result).contains("matches=0"))
        assertFalse(result.summary().contains("SECRET"))
    }

    @Test fun `listing failure remains separate from zero visible matches even after one match`() {
        val result = scanEntries(entries = { visit ->
            visit("42"); throw AccessDeniedException("PRIVATE PATH")
        }, openCmdline = { ByteArrayInputStream("qtandroidnative\u0000".toByteArray()) })
        assertEquals(listOf("42"), result.candidates)
        val detail = refused(result)
        assertTrue(detail.contains("list=DENIED:DENIED")); assertTrue(detail.contains("matches=1"))
        assertFalse(detail.contains("PRIVATE PATH"))
    }

    @Test fun `denied vanished and other failed reads retain distinct bounded causes`() {
        val result = scan("1", "2", "3", "4") {
            when (it) {
                "1" -> throw AccessDeniedException("PRIVATE PATH")
                "2" -> throw NoSuchFileException("PRIVATE PATH")
                "3" -> throw IOException("SECRET ERROR MESSAGE")
                else -> "other\u0000".toByteArray()
            }
        }
        assertEquals(1, result.denied); assertEquals(1, result.vanished); assertEquals(1, result.errors)
        assertEquals(1, result.readable)
        val detail = refused(result)
        assertTrue(detail.contains("first=cmdline:DENIED"))
        assertTrue(detail.contains("unavailable is not absent"))
        assertFalse(detail.contains("PRIVATE")); assertFalse(detail.contains("SECRET"))
    }

    @Test fun `unreadable unrelated processes do not replace the remaining native admission checks`() {
        val result = scan("1", "2") {
            if (it == "1") throw AccessDeniedException("denied")
            "qtandroidnative\u0000".toByteArray()
        }
        assertEquals("2", result.requireSingle()); assertEquals(1, result.denied)
    }

    @Test fun `empty unterminated and oversized argv0 are distinct and cannot match`() {
        val result = scan("1", "2", "3", "4") {
            when (it) {
                "1" -> byteArrayOf()
                "2" -> "\u0000PRIVATE ARG".toByteArray()
                "3" -> "qtandroidnative".toByteArray()
                else -> ("x".repeat(ClusterRendererDiscovery.MAX_ARGV0_BYTES + 1) + "\u0000").toByteArray()
            }
        }
        assertEquals(2, result.empty); assertEquals(1, result.noNul); assertEquals(1, result.tooLong)
        assertTrue(result.candidates.isEmpty()); refused(result)
    }

    @Test fun `short reads and a terminator at the byte bound are handled without another argument`() {
        val argv0 = "/".repeat(ClusterRendererDiscovery.MAX_ARGV0_BYTES - 15) + "qtandroidnative"
        val input = object : ByteArrayInputStream((argv0 + "\u0000PRIVATE ARG").toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(1, len))
        }
        val result = scanEntries({ it("1") }, { input })
        assertEquals("1", result.requireSingle()); assertEquals(0, result.tooLong)
        assertEquals("PRIVATE ARG".length, input.available())
    }

    @Test fun `oversized argv0 reads at most the declared bound plus one and closes the stream`() {
        var read = 0; var closed = false
        val input = object : ByteArrayInputStream(ByteArray(10000) { 1 }) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
            override fun close() { closed = true; super.close() }
        }
        val result = scanEntries({ it("1") }, { input })
        assertEquals(ClusterRendererDiscovery.MAX_ARGV0_BYTES + 1, read)
        assertTrue(closed); assertEquals(1, result.tooLong); refused(result)
    }

    @Test fun `multiple matches are refused rather than selecting an arbitrary renderer`() {
        val result = scan("1", "2") { "qtandroidnative\u0000".toByteArray() }
        assertTrue(refused(result).contains("matches=2"))
    }

    @Test fun `PID and entry caps refuse partial enumeration even with a readable candidate`() {
        var opened = 0
        val pids = scanEntries({ visit ->
            repeat(ClusterRendererDiscovery.MAX_PIDS + 1) { visit((it + 1).toString()) }
        }, { pid -> opened++; ByteArrayInputStream((if (pid == "1") "qtandroidnative\u0000" else "other\u0000").toByteArray()) })
        assertEquals(ClusterRendererDiscovery.MAX_PIDS, opened)
        assertTrue(refused(pids).contains("stop=PID_LIMIT")); assertEquals(listOf("1"), pids.candidates)
        val entries = scanEntries({ visit ->
            visit("1"); repeat(ClusterRendererDiscovery.MAX_ENTRIES) { visit("self") }
        }, { ByteArrayInputStream("qtandroidnative\u0000".toByteArray()) })
        assertTrue(refused(entries).contains("stop=ENTRY_LIMIT"))
    }

    @Test fun `deadline during cmdline closes the stream and refuses partial enumeration`() {
        var now = 0L; var closed = false
        val input = object : ByteArrayInputStream("qtandroidnative\u0000".toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                now = ClusterRendererDiscovery.SCAN_MS
                return super.read(b, off, minOf(1, len))
            }
            override fun close() { closed = true; super.close() }
        }
        val result = scanEntries({ it("1") }, { input }, clock = { now })
        assertTrue(closed); assertTrue(refused(result).contains("stop=TIME_LIMIT"))
    }
}
