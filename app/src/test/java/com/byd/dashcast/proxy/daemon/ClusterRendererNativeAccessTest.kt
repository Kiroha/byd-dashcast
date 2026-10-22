package com.byd.dashcast.proxy.daemon

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.byd.dashcast.clusterlive.ClusterRendererDiscovery
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.FileDescriptor
import java.util.IdentityHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ClusterRendererNativeAccessTest.ProcOs::class])
class ClusterRendererNativeAccessTest {
    @Implements(Os::class)
    class ProcOs {
        companion object {
            val opened = IdentityHashMap<FileDescriptor, Int>()
            val closes = IdentityHashMap<FileDescriptor, Int>()
            var contents = "qtandroidnative\u0000PRIVATE".toByteArray()
            var refuseOpen = false
            var failRead = false
            var failClose = false
            var fatalRead = false
            var now = 0L
            var expireRead = false

            @JvmStatic @Implementation fun open(path: String, flags: Int, mode: Int): FileDescriptor {
                assertTrue(path.matches(Regex("/proc/[1-9][0-9]{0,8}/cmdline")))
                assertEquals(OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, flags)
                assertEquals(0, mode)
                if (refuseOpen) throw ErrnoException("open", OsConstants.EACCES)
                return FileDescriptor().also { opened[it] = 0 }
            }
            @JvmStatic @Implementation fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int): Int {
                assertTrue(opened.containsKey(fd)); assertFalse(closes.containsKey(fd))
                if (failRead) throw ErrnoException("read", OsConstants.EIO)
                if (fatalRead) throw AssertionError("fatal read")
                if (expireRead) now = ClusterRendererDiscovery.SCAN_MS
                val position = opened.getValue(fd)
                val n = minOf(count, contents.size - position, if (expireRead) 1 else count)
                contents.copyInto(bytes, offset, position, position + n)
                opened[fd] = position + n
                return n // Android Os.read returns zero at EOF.
            }
            @JvmStatic @Implementation fun close(fd: FileDescriptor) {
                assertTrue(opened.containsKey(fd))
                closes[fd] = (closes[fd] ?: 0) + 1
                if (failClose) throw ErrnoException("close", OsConstants.EIO)
            }
        }
    }

    @Before fun reset() {
        ProcOs.opened.clear(); ProcOs.closes.clear()
        ProcOs.contents = "qtandroidnative\u0000PRIVATE".toByteArray()
        ProcOs.refuseOpen = false; ProcOs.failRead = false; ProcOs.failClose = false; ProcOs.fatalRead = false
        ProcOs.now = 0; ProcOs.expireRead = false
    }
    @Suppress("UNCHECKED_CAST")
    private fun access() = ClusterThemeLiveTest::class.java.getDeclaredField("rendererAccess")
        .apply { isAccessible = true }.get(null) as ClusterRendererDiscovery.Access<FileDescriptor>
    private fun scan(entries: ((String) -> Unit) -> Unit = { it("42") }) =
        ClusterRendererDiscovery.scan(entries, access(), clock = { ProcOs.now })
    private fun closedOnce(count: Int) {
        assertEquals(count, ProcOs.opened.size); assertEquals(count, ProcOs.closes.size)
        assertTrue(ProcOs.closes.values.all { it == 1 })
    }

    @Test fun `actual Android adapter closes native FD explicitly on successful and empty reads`() {
        assertEquals("42", scan().requireSingle()); closedOnce(1)
        ProcOs.contents = byteArrayOf()
        assertEquals(1, scan().empty); closedOnce(2)
    }
    @Test fun `native read failures and fatal processing errors still close exactly once`() {
        ProcOs.failRead = true
        assertEquals(1, scan().errors); closedOnce(1)
        ProcOs.failRead = false; ProcOs.fatalRead = true
        assertTrue(runCatching { scan() }.exceptionOrNull() is AssertionError); closedOnce(2)
    }
    @Test fun `native open failure owns no FD and performs no close`() {
        ProcOs.refuseOpen = true
        assertTrue(scan().candidates.isEmpty()); closedOnce(0)
    }
    @Test fun `oversized argv0 and deadline both release the native descriptor`() {
        ProcOs.contents = ByteArray(10000) { 1 }
        assertEquals(1, scan().tooLong); closedOnce(1)
        assertEquals(ClusterRendererDiscovery.MAX_ARGV0_BYTES + 1, ProcOs.opened.values.single())
        ProcOs.expireRead = true
        assertEquals("TIME_LIMIT", scan().stop); closedOnce(2)
    }
    @Test fun `PID cap does not leak native descriptors across the maximum scan`() {
        val result = scan { visit -> repeat(ClusterRendererDiscovery.MAX_PIDS + 1) { visit((it + 1).toString()) } }
        assertEquals("PID_LIMIT", result.stop); closedOnce(ClusterRendererDiscovery.MAX_PIDS)
    }
    @Test fun `a native close failure is recorded and never retried against a reusable FD number`() {
        ProcOs.failClose = true
        assertEquals(1, scan().errors); closedOnce(1)
    }
}
