package com.byd.dashcast.clusterlive

import org.junit.Assert.*
import org.junit.Test

class ClusterThemeRetirementTest {
    private val intent = ClusterThemeRetirement(".dashcast-retired-" + "b".repeat(32), 1, 10, false)
    private class Files : ClusterThemeRetirement.Files {
        val bytes = Any()
        var original: Any? = bytes
        var target: Any? = null
        var moves = 0
        var syncs = 0
        var failSync = -1
        var failRename = false
        override fun sync() { check(++syncs != failSync) { "fsync unavailable" } }
        override fun originalAbsent() = original == null
        override fun targetAbsent() = target == null
        override fun verifyOriginal() { check(original === bytes) { "Original inode/hash/type changed" } }
        override fun verifyTarget() { check(target === bytes) { "Retired inode/hash/type changed" } }
        override fun rename() {
            check(!failRename) { "rename failed" }
            check(target == null); target = original; original = null; moves++
        }
    }
    @Test fun `rename follows durable intent and preserves the exact object once`() {
        val fs = Files(); val writes = ArrayList<ClusterThemeRetirement>()
        val result = intent.finish(false, fs) {
            if (!it.moved) assertEquals(0, fs.moves) else assertEquals(1, fs.moves)
            writes.add(it)
        }
        assertEquals(listOf(false, true), writes.map { it.moved }); assertTrue(result.moved)
        assertNull(fs.original); assertSame(fs.bytes, fs.target); assertEquals(2, fs.syncs)
        assertThrows(IllegalStateException::class.java) { result.finish(true, fs) { fail("No repeat rename") } }
        assertEquals(1, fs.moves)
    }
    @Test fun `failed intent cannot authorize rename through an in memory retry`() {
        val fs = Files()
        repeat(2) { attempt ->
            assertThrows(IllegalStateException::class.java) { intent.finish(attempt != 0, fs) { error("journal failed") } }
            assertEquals(0, fs.moves); assertSame(fs.bytes, fs.original)
        }
        var writes = 0
        assertTrue(intent.finish(true, fs) { writes++ }.moved)
        assertEquals(2, writes); assertEquals(1, fs.moves)
    }
    @Test fun `crash after rename before completion checkpoint reconciles without moving twice`() {
        val fs = Files(); var saved: ClusterThemeRetirement? = null
        assertThrows(IllegalStateException::class.java) { intent.finish(false, fs) {
            if (it.moved) error("completion journal failed") else saved = it
        } }
        assertEquals(intent, saved); assertEquals(1, fs.moves); assertSame(fs.bytes, fs.target)
        assertTrue(saved!!.finish(true, fs) { saved = it }.moved)
        assertEquals(1, fs.moves); assertSame(fs.bytes, fs.target)
    }
    @Test fun `source target or lease changed during intent persistence prevents rename`() {
        for (fault in 0..2) {
            val fs = Files()
            assertThrows(IllegalStateException::class.java) { intent.finish(false, fs) {
                when (fault) { 0 -> fs.original = Any(); 1 -> fs.target = Any(); 2 -> error("Projection lease expired after fsync") }
            } }
            assertEquals(0, fs.moves)
        }
    }
    @Test fun `rename and directory sync failures retain an explicit resumable intent`() {
        for (failure in 0..2) {
            val fs = Files().apply { failRename = failure == 0; failSync = if (failure == 1) 1 else if (failure == 2) 2 else -1 }
            var saved: ClusterThemeRetirement? = null
            assertThrows(IllegalStateException::class.java) { intent.finish(false, fs) { saved = it } }
            assertFalse(saved?.moved == true)
            if (failure == 1) { assertNull(saved); assertEquals(0, fs.moves) }
            fs.failRename = false; fs.failSync = -1
            assertTrue(intent.finish(saved != null, fs) { saved = it }.moved)
            assertEquals(1, fs.moves); assertSame(fs.bytes, fs.target)
        }
    }
    @Test fun `foreign target substituted original and absent objects cannot complete retirement`() {
        for (failure in 0..3) {
            val fs = Files().apply {
                when (failure) {
                    0 -> target = Any()
                    1 -> original = Any() // Same name with another inode/hash/type, including a symlink.
                    2 -> original = null
                    3 -> { original = null; target = Any() }
                }
            }
            assertThrows(IllegalStateException::class.java) { intent.finish(failure >= 2, fs) { fail("No completed/invalid intent") } }
            assertEquals(0, fs.moves)
        }
    }
    @Test fun `retirement eligibility requires real native error slot and persisted own path`() {
        val name = ClusterThemePolicy.PREFIX + "a".repeat(32) + ".rcc"
        val own = ClusterThemeProtocol.Response(1001, 4, listOf(1, 0, 0, 0, 0),
            listOf(ClusterThemeProtocol.UI_VERSION, "", ClusterThemePolicy.HOST_DIRECTORY + name, "", "DashCast_a"))
        val same = ClusterThemeProtocol.Response(1005, 0, listOf(3, 0, 0x2330, 0, 0), List(5) { "" })
        assertTrue(ClusterThemeRetirement.eligible(name, own, listOf(same), 0, true))
        for (wrong in listOf(same.copy(ints = listOf(3, 0x2330, 0, 0, 0)), same.copy(ints = listOf(3, 0, 0x232e, 0, 0)),
            same.copy(ints = listOf(3, 0, 0x2330, 1, 0)), same.copy(strings = listOf("unexpected", "", "", "", ""))))
            assertFalse(ClusterThemeRetirement.eligible(name, own, listOf(wrong), 0, true))
        assertFalse(ClusterThemeRetirement.eligible(name, own.copy(ints = listOf(1, 0, 0, 1, 0)), listOf(same), 0, true))
        assertFalse(ClusterThemeRetirement.eligible(name, own, listOf(same, same.copy(ints = listOf(2, 0, 0, 0, 0))), 0, true))
        assertFalse(ClusterThemeRetirement.eligible(name, own, listOf(same), -1, true))
        assertFalse(ClusterThemeRetirement.eligible(name, own, listOf(same), 0, false))
    }
}
