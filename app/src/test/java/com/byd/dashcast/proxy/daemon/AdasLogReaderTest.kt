package com.byd.dashcast.proxy.daemon

import com.byd.dashcast.proxy.AdasLogProbePolicy as Policy
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.AccessDeniedException

class AdasLogReaderTest {
    private val root = Policy.roots.first()
    private val line = "10-21 13:33:11 123 123 I CLUSTER_LOG: [callbackReal] id=500(leftFrontTargetPosX), value=-1.250000\n"
    private class Fixture : AdasLogReader.Access<String> {
        val dirs = Policy.roots.flatMap { path -> path.split('/').filter(String::isNotEmpty).indices.map { "/" + path.split('/').filter(String::isNotEmpty).take(it + 1).joinToString("/") } }.toSet()
        val files = linkedMapOf<String, ByteArray>()
        val modes = mutableMapOf<String, Int>()
        val opened = mutableListOf<String>()
        var closed = 0
        var reads = 0
        var bytes = 0
        var denied: String? = null
        var mutate: ((String, Int) -> AdasLogReader.Stamp?)? = null
        var onOpen: ((String) -> Unit)? = null
        private val fdStats = mutableMapOf<String, Int>()
        override fun stat(path: String): AdasLogReader.Stamp {
            if (path == denied) throw AccessDeniedException(path)
            if (path in dirs) return AdasLogReader.Stamp(1, path.hashCode().toLong(), 0x4000, 0, 1)
            val payload = files[path] ?: throw java.nio.file.NoSuchFileException(path)
            return AdasLogReader.Stamp(1, path.hashCode().toLong(), modes[path] ?: 0x8000, payload.size.toLong(), 1)
        }
        override fun children(path: String, visit: (String) -> Boolean) {
            for (file in files.keys.filter { it.substringBeforeLast('/') == path }) if (!visit(file.substringAfterLast('/'))) break
        }
        override fun open(path: String): String {
            if (path == denied) throw AccessDeniedException(path)
            opened += path; onOpen?.invoke(path); return path
        }
        override fun statFd(fd: String): AdasLogReader.Stamp {
            val count = (fdStats[fd] ?: 0) + 1; fdStats[fd] = count
            return mutate?.invoke(fd, count) ?: stat(fd)
        }
        override fun read(fd: String, bytes: ByteArray, offset: Int, count: Int, position: Long): Int {
            reads++; val payload = files.getValue(fd)
            val amount = minOf(count, payload.size - position.toInt())
            payload.copyInto(bytes, offset, position.toInt(), position.toInt() + amount)
            this.bytes += amount; return amount
        }
        override fun close(fd: String) { closed++ }
    }

    @Test fun `closed filenames and exact decoded callback identity exclude unrelated private text`() {
        val f = Fixture()
        f.files["$root/aplog"] = ("private unrelated location=SECRET\n" + line +
            "I CLUSTER_LOG: [callbackInt] id=499(leftFrontTargetState), value=1\n" +
            "I CLUSTER_LOG: [callbackReal] id=500(rightFrontTargetPosX), value=9.000000\n").toByteArray()
        f.files["$root/aplog.01"] = line.toByteArray()
        for (name in listOf("aplog.backup", "aplog.11", "private", "aplog.1.gz")) f.files["$root/$name"] = line.toByteArray()
        val result = AdasLogReader.collect(f)
        assertEquals(2, f.opened.size); assertEquals(2, f.closed)
        assertTrue(result.contains("matches=3")); assertTrue(result.contains("value=-1.250000"))
        assertFalse(result.contains("SECRET")); assertFalse(result.contains("value=9.000000"))
        assertEquals(2, Regex("DIRECTORY_METADATA_ONLY").findAll(result).count())
        assertTrue(f.opened.all { it.startsWith("$root/") })
    }

    @Test fun `all 21 mappings enforce exact ID name kind finite values and complete records`() {
        val groups = listOf("leftFrontTarget", "rightFrontTarget", "leftRearTarget", "rightRearTarget", "frontTargetOne", "frontTargetTwo", "rearTarget")
        for ((index, group) in groups.withIndex()) for ((offset, suffix) in listOf("State", "PosX", "PosY").withIndex()) {
            val id = 499 + index * 3 + offset
            val kind = if (offset == 0) "Int" else "Real"
            val value = if (offset == 0) "4" else "-12.500000"
            assertNotNull(Policy.targetLine("I CLUSTER_LOG: [callback$kind] id=$id($group$suffix), value=$value"))
        }
        for (bad in listOf("I OTHER: [callbackInt] id=499(leftFrontTargetState), value=1",
            "I CLUSTER_LOG: [callbackReal] id=499(leftFrontTargetState), value=1.000000",
            "I CLUSTER_LOG: [callbackInt] id=499(leftFrontTargetState), value=99",
            "I CLUSTER_LOG: [callbackReal] id=500(leftFrontTargetPosX), value=1.0",
            "I CLUSTER_LOG: [callbackReal] id=500(leftFrontTargetPosX), value=NaN",
            "I CLUSTER_LOG: [callbackReal] id=500(leftFrontTargetPosX), value=1.000000 private")) assertNull(Policy.targetLine(bad))
        val f = Fixture()
        f.files["$root/aplog"] = (line + line.trimEnd()).toByteArray()
        assertTrue(AdasLogReader.collect(f).contains("matches=1"))
    }

    @Test fun `empty readable denied symlink FIFO and changed fd are distinct and close handles`() {
        val f = Fixture()
        f.files["$root/aplog"] = byteArrayOf()
        f.files["$root/aplog.1"] = line.toByteArray(); f.denied = "$root/aplog.1"
        f.files["$root/aplog.2"] = line.toByteArray(); f.modes["$root/aplog.2"] = 0xa000
        f.files["$root/aplog.3"] = line.toByteArray(); f.modes["$root/aplog.3"] = 0x1000
        f.files["$root/aplog.4"] = line.toByteArray()
        f.mutate = { fd, n -> if (fd.endsWith(".4") && n == 1) f.stat(fd).copy(mode = 0x2000) else null }
        val result = AdasLogReader.collect(f)
        assertTrue(result.contains("EMPTY_READABLE")); assertTrue(result.contains("DENIED"))
        assertTrue(result.contains("FILE_CHANGED_BEFORE_READ")); assertTrue(result.contains("REJECTED_NONREGULAR"))
        assertEquals(2, f.opened.size); assertEquals(2, f.closed); assertEquals(0, f.reads)
    }

    @Test fun `append is marked non atomic but truncation discards entire file records`() {
        for (truncate in listOf(false, true)) {
            val f = Fixture(); f.files["$root/aplog"] = line.toByteArray()
            f.mutate = { fd, n -> if (n == 2) f.stat(fd).copy(size = if (truncate) 0 else f.stat(fd).size + 50) else null }
            val result = AdasLogReader.collect(f)
            assertTrue(result.contains(if (truncate) "FILE_CHANGED_DURING_READ" else "appended=true"))
            assertTrue(result.contains(if (truncate) "matches=0" else "matches=1")); assertEquals(1, f.closed)
        }
    }

    @Test fun `tail truncation flag uses opened file size after growth since directory scan`() {
        val f = Fixture(); f.files["$root/aplog"] = line.toByteArray()
        f.onOpen = { path -> f.files[path] = ("X".repeat(Policy.TAIL_BYTES) + "\n" + line).toByteArray() }
        val result = AdasLogReader.collect(f)
        assertTrue(result.contains("tailLimit=true")); assertTrue(result.contains("matches=1"))
        assertEquals(Policy.TAIL_BYTES, f.bytes)
    }

    @Test fun `ancestor symlink prevents any open and parent identity change discards records`() {
        for (changed in listOf(false, true)) {
            val f = Fixture(); f.files["$root/aplog"] = line.toByteArray()
            val guarded = object : AdasLogReader.Access<String> by f {
                override fun stat(path: String): AdasLogReader.Stamp {
                    val stamp = f.stat(path)
                    return if (path == "/data/logs") {
                        if (!changed) stamp.copy(mode = 0xa000)
                        else if (f.reads > 0) stamp.copy(inode = stamp.inode + 1) else stamp
                    } else stamp
                }
            }
            val result = AdasLogReader.collect(guarded)
            assertTrue(result.contains(if (changed) "PARENT_CHANGED" else "REJECTED_PARENT_TYPE_OR_SYMLINK"))
            assertFalse(result.contains("record file="))
            assertEquals(if (changed) 1 else 0, f.opened.size)
            assertEquals(f.opened.size, f.closed)
        }
    }

    @Test fun `tail files entries matches response and deadline are bounded`() {
        val f = Fixture()
        for (index in 1..10) f.files["$root/aplog.$index"] = ("X".repeat(Policy.TAIL_BYTES) + "\n" + line.repeat(100)).toByteArray()
        val result = AdasLogReader.collect(f)
        assertEquals(Policy.MAX_FILES, f.opened.size)
        assertEquals(Policy.MAX_FILES * Policy.TAIL_BYTES, f.bytes)
        assertTrue(result.contains("fileLimit=true")); assertTrue(result.contains("matches=64"))
        assertTrue(result.length <= Policy.MAX_RESPONSE)
        val tooMany = Fixture()
        for (index in 1..200) tooMany.files["$root/irrelevant$index"] = byteArrayOf()
        assertTrue(AdasLogReader.collect(tooMany).contains("entryLimit=true")); assertEquals(0, tooMany.reads)
        var now = 0L
        assertTrue(AdasLogReader.collect(f) { now.also { now += Policy.IO_BUDGET_MS } }.contains("TIME_BUDGET"))
    }
}
