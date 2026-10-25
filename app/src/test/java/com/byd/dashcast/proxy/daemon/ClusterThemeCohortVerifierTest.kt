package com.byd.dashcast.proxy.daemon

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.byd.dashcast.clusterlive.ClusterThemeCohort
import com.byd.dashcast.clusterlive.ClusterThemeVariant
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.FileDescriptor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ClusterThemeCohortVerifierTest.CohortOs::class])
class ClusterThemeCohortVerifierTest {
    @Implements(Os::class)
    class CohortOs {
        companion object {
            val paths = ClusterThemeCohort.expected(ClusterThemeVariant.CORNER_MARKER_21F7735C).keys.toList()
            val opened = LinkedHashMap<FileDescriptor, Int>()
            val offsets = HashMap<FileDescriptor, Int>()
            val closed = ArrayList<FileDescriptor>()
            var badType = false; var badUid = false; var badSize = false; var failStat = false
            var expireAfterRead = false; var failRead = false; var replacePath = false; var lateReplace = false; var failOpen = false
            val bytes = "not-the-compiled-artifact".toByteArray()
            @JvmStatic @Implementation fun open(path: String, flags: Int, mode: Int): FileDescriptor {
                assertEquals(paths[opened.size], path)
                assertEquals(OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, flags)
                assertEquals(0, mode)
                if (failOpen && opened.size == 2) throw ErrnoException("open", OsConstants.EACCES)
                return FileDescriptor().also { opened[it] = opened.size; offsets[it] = 0 }
            }
            private fun stat(index: Int, replaced: Boolean = false) = StructStat(1L, index + if (replaced) 99L else 2L,
                (if (badType) OsConstants.S_IFLNK else OsConstants.S_IFREG) or 420, 1L,
                if (badUid) 2000 else 0, 0, 0L, if (badSize) Long.MAX_VALUE else bytes.size.toLong(), 0L, 1L, 2L, 4096L, 1L)
            @JvmStatic @Implementation fun fstat(fd: FileDescriptor): StructStat {
                if (failStat) throw ErrnoException("fstat", OsConstants.EIO)
                return stat(opened.getValue(fd))
            }
            @JvmStatic @Implementation fun lstat(path: String): StructStat = stat(paths.indexOf(path),
                replacePath || lateReplace && opened.size == paths.size && path == paths.first())
            @JvmStatic @Implementation fun read(fd: FileDescriptor, output: ByteArray, start: Int, count: Int): Int {
                if (failRead) throw ErrnoException("read", OsConstants.EIO)
                val at = offsets.getValue(fd); val n = minOf(count, bytes.size - at)
                bytes.copyInto(output, start, at, at + n); offsets[fd] = at + n
                if (expireAfterRead) org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(2))
                return n
            }
            @JvmStatic @Implementation fun close(fd: FileDescriptor) { assertFalse(closed.contains(fd)); closed.add(fd) }
        }
    }
    @Before fun reset() {
        CohortOs.opened.clear(); CohortOs.offsets.clear(); CohortOs.closed.clear()
        CohortOs.badType = false; CohortOs.badUid = false; CohortOs.badSize = false; CohortOs.failStat = false
        CohortOs.expireAfterRead = false; CohortOs.failRead = false; CohortOs.replacePath = false; CohortOs.lateReplace = false; CohortOs.failOpen = false
    }
    private fun failure(deadline: Long = Long.MAX_VALUE) = assertThrows(Exception::class.java) {
        ClusterThemeCohortVerifier.verify(ClusterThemeVariant.CORNER_MARKER_21F7735C, deadline)
    }
    @Test fun `wrong hashes read exactly five fixed pinned artifacts then close every fd`() {
        assertTrue(failure().message!!.contains("cohort 21f7735c differs"))
        assertEquals(5, CohortOs.opened.size); assertEquals(5, CohortOs.closed.size)
    }
    @Test fun `nonregular nonroot and oversized artifacts fail before reading and close once`() {
        for (case in 0..2) {
            reset(); CohortOs.badType = case == 0; CohortOs.badUid = case == 1; CohortOs.badSize = case == 2
            assertTrue(failure().message!!.contains("Unsupported installed cohort artifact"))
            assertEquals(1, CohortOs.closed.size); assertEquals(listOf(0), CohortOs.offsets.values.toList())
        }
    }
    @Test fun `open stat and read failures close all already opened handles`() {
        CohortOs.failOpen = true; assertTrue(failure() is ErrnoException); assertEquals(2, CohortOs.closed.size)
        reset(); CohortOs.failStat = true; assertTrue(failure() is ErrnoException); assertEquals(1, CohortOs.closed.size)
        reset(); CohortOs.failRead = true; assertTrue(failure() is ErrnoException); assertEquals(1, CohortOs.closed.size)
    }
    @Test fun `path replacement during hashing and replacement of an earlier artifact at completion are refused`() {
        CohortOs.replacePath = true; assertTrue(failure().message!!.contains("changed while hashing")); assertEquals(1, CohortOs.closed.size)
        reset(); CohortOs.lateReplace = true
        assertTrue(failure().message!!.contains("changed before completion")); assertEquals(5, CohortOs.closed.size)
    }
    @Test fun `deadline during hashing closes the active fd without reading another artifact`() {
        CohortOs.expireAfterRead = true
        assertTrue(failure(android.os.SystemClock.elapsedRealtime() + 1).message!!.contains("deadline"))
        assertEquals(1, CohortOs.opened.size); assertEquals(1, CohortOs.closed.size)
    }
    @Test fun `expired deadline opens no path`() {
        assertTrue(failure(0).message!!.contains("deadline")); assertTrue(CohortOs.opened.isEmpty())
    }
}
