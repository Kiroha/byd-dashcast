package com.byd.dashcast.proxy.daemon

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.io.FileDescriptor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ClusterThemeHelperReadinessFileTest.ReadyOs::class])
class ClusterThemeHelperReadinessFileTest {
    @Implements(Os::class)
    class ReadyOs {
        companion object {
            var bytes = "{}".toByteArray(); var offset = 0; var opens = 0; var closes = 0; var stats = 0
            var badType = false; var badOwner = false; var replaced = false; var growing = false
            var symlink = false; var absent = false; var failRead = false; var failStat = false
            @JvmStatic @Implementation fun open(path: String, flags: Int, mode: Int): FileDescriptor {
                assertEquals(OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, flags)
                if (symlink) throw ErrnoException("open", OsConstants.ELOOP)
                if (absent) throw ErrnoException("open", OsConstants.ENOENT)
                opens++; offset = 0
                return FileDescriptor()
            }
            private fun stat(named: Boolean = false) = StructStat(1, if (named && replaced) 99 else 2,
                (if (badType) OsConstants.S_IFDIR else OsConstants.S_IFREG) or 384, 1,
                Process.myUid() + if (badOwner) 1 else 0, 0, 0,
                bytes.size.toLong() + if (!named && growing && stats > 1) 1 else 0, 0, 0, 0, 4096, 1)
            @JvmStatic @Implementation fun fstat(fd: FileDescriptor): StructStat {
                if (failStat) throw ErrnoException("fstat", OsConstants.EIO)
                stats++; return stat()
            }
            @JvmStatic @Implementation fun lstat(path: String) = stat(true)
            @JvmStatic @Implementation fun read(fd: FileDescriptor, output: ByteArray, start: Int, count: Int): Int {
                if (failRead) throw ErrnoException("read", OsConstants.EIO)
                val n = minOf(count, bytes.size - offset); bytes.copyInto(output, start, offset, offset + n); offset += n; return n
            }
            @JvmStatic @Implementation fun close(fd: FileDescriptor) { closes++ }
        }
    }
    private val file = File("/private/helper-identity.json")
    @Before fun reset() {
        ReadyOs.bytes = "{}".toByteArray(); ReadyOs.offset = 0; ReadyOs.opens = 0; ReadyOs.closes = 0; ReadyOs.stats = 0
        ReadyOs.badType = false; ReadyOs.badOwner = false; ReadyOs.replaced = false; ReadyOs.growing = false
        ReadyOs.symlink = false; ReadyOs.absent = false; ReadyOs.failRead = false; ReadyOs.failStat = false
    }
    @Test fun `symlink and foreign file identities refuse without admission`() {
        ReadyOs.symlink = true; assertThrows(ErrnoException::class.java) { ClusterThemeHelperReadiness.read(file) }; assertEquals(0, ReadyOs.closes)
        for (case in 0..2) {
            reset(); ReadyOs.badType = case == 0; ReadyOs.badOwner = case == 1; ReadyOs.replaced = case == 2
            assertThrows(IllegalStateException::class.java) { ClusterThemeHelperReadiness.read(file) }; assertEquals(1, ReadyOs.closes)
        }
    }
    @Test fun `missing empty partial or growing record can only wait and every opened fd closes`() {
        ReadyOs.absent = true; assertNull(ClusterThemeHelperReadiness.read(file)); assertEquals(0, ReadyOs.closes)
        for (text in listOf("", "{\"token\":", "{}")) {
            reset(); ReadyOs.bytes = text.toByteArray(); ReadyOs.growing = text == "{}"
            assertNull(ClusterThemeHelperReadiness.read(file)); assertEquals(1, ReadyOs.closes)
        }
    }
    @Test fun `oversized record and read failure close fd while stable bounded JSON is returned`() {
        ReadyOs.bytes = ByteArray(1025)
        assertThrows(IllegalStateException::class.java) { ClusterThemeHelperReadiness.read(file) }; assertEquals(1, ReadyOs.closes); assertEquals(0, ReadyOs.offset)
        reset(); ReadyOs.failRead = true
        assertThrows(ErrnoException::class.java) { ClusterThemeHelperReadiness.read(file) }; assertEquals(1, ReadyOs.closes)
        reset(); ReadyOs.failStat = true
        assertThrows(ErrnoException::class.java) { ClusterThemeHelperReadiness.read(file) }; assertEquals(1, ReadyOs.closes)
        reset(); assertEquals(0, ClusterThemeHelperReadiness.read(file)!!.length()); assertEquals(1, ReadyOs.closes)
    }
    @Test fun `incomplete record deadline closes every retry descriptor`() {
        ReadyOs.bytes = "{\"token\":".toByteArray()
        var time = 0L
        assertThrows(IllegalStateException::class.java) {
            ClusterThemeHelperReadiness.await("a".repeat(32), "owned.rcc", 3000, { true },
                { ClusterThemeHelperReadiness.read(file) }, { time }, { time += 25 })
        }
        assertEquals(120, ReadyOs.opens); assertEquals(ReadyOs.opens, ReadyOs.closes)
    }
}
