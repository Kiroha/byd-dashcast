package com.byd.dashcast.proxy.daemon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.byd.dashcast.clusterlive.ClusterThemePolicy
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.FileDescriptor
import java.lang.reflect.InvocationTargetException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ClusterThemeQueryIdentityTest.ApkOs::class])
class ClusterThemeQueryIdentityTest {
    @Implements(Os::class)
    class ApkOs {
        companion object {
            var opens = 0
            var closes = 0
            var offset = 0
            var failRead = false
            var replacePath = false
            val bytes = "wrong".toByteArray()
            @JvmStatic @Implementation fun open(path: String, flags: Int, mode: Int): FileDescriptor {
                assertEquals("/system/app/XdjaContainerService/XdjaContainerService.apk", path)
                assertFalse(path.contains("/proc/"))
                assertEquals(OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, flags)
                opens++; return FileDescriptor()
            }
            private fun stat(inode: Long = 2) = StructStat(1L, inode, OsConstants.S_IFREG or 420, 1L,
                0, 0, 0L, bytes.size.toLong(), 0L, 1L, 0L, 4096L, 1L)
            @JvmStatic @Implementation fun fstat(fd: FileDescriptor) = stat()
            @JvmStatic @Implementation fun lstat(path: String) = stat(if (replacePath) 3L else 2L)
            @JvmStatic @Implementation fun read(fd: FileDescriptor, output: ByteArray, start: Int, count: Int): Int {
                if (failRead) throw ErrnoException("read", OsConstants.EIO)
                val n = minOf(count, bytes.size - offset)
                bytes.copyInto(output, start, offset, offset + n); offset += n; return n
            }
            @JvmStatic @Implementation fun close(fd: FileDescriptor) { closes++ }
        }
    }
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var installed: PackageInfo
    @Before fun reset() {
        ApkOs.opens = 0; ApkOs.closes = 0; ApkOs.offset = 0; ApkOs.failRead = false; ApkOs.replacePath = false
        installed = PackageInfo().apply {
            packageName = ClusterThemePolicy.JAVA_PACKAGE
            applicationInfo = ApplicationInfo().apply {
                packageName = ClusterThemePolicy.JAVA_PACKAGE; uid = 1000; flags = ApplicationInfo.FLAG_SYSTEM
                sourceDir = "/system/app/XdjaContainerService/XdjaContainerService.apk"
            }
        }
        shadowOf(app.packageManager).installPackage(installed)
    }
    private fun failure(): Throwable {
        val method = ClusterThemeQueryHelper::class.java.getDeclaredMethod("installedIdentity", Context::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        return (assertThrows(InvocationTargetException::class.java) {
            method.invoke(ClusterThemeQueryHelper, app, Long.MAX_VALUE)
        }).cause!!
    }
    @Test fun `wrong installed UID source or splits fail before opening an APK`() {
        installed.applicationInfo!!.uid = 2000
        assertTrue(failure().message!!.contains("UID/source/splits")); assertEquals(0, ApkOs.opens)
        installed.applicationInfo!!.uid = 1000; installed.applicationInfo!!.sourceDir = "relative.apk"
        assertTrue(failure().message!!.contains("UID/source/splits")); assertEquals(0, ApkOs.opens)
        installed.applicationInfo!!.sourceDir = "/system/app/XdjaContainerService/XdjaContainerService.apk"
        installed.applicationInfo!!.splitSourceDirs = arrayOf("/unknown/split.apk")
        assertTrue(failure().message!!.contains("UID/source/splits")); assertEquals(0, ApkOs.opens)
    }
    @Test fun `an installed non-system package is refused even with UID 1000`() {
        installed.applicationInfo!!.flags = ApplicationInfo.FLAG_INSTALLED
        assertTrue(failure().message!!.contains("UID/source/splits")); assertEquals(0, ApkOs.opens)
    }
    @Test fun `wrong installed APK SHA is refused and the native FD closes exactly once`() {
        assertTrue(failure().message!!.contains("APK differs"))
        assertEquals(1, ApkOs.opens); assertEquals(1, ApkOs.closes)
    }
    @Test fun `replaced APK inode and read error also close exactly once`() {
        ApkOs.replacePath = true
        assertTrue(failure().message!!.contains("changed while hashing")); assertEquals(1, ApkOs.closes)
        ApkOs.replacePath = false; ApkOs.offset = 0; ApkOs.failRead = true
        assertTrue(failure() is ErrnoException); assertEquals(2, ApkOs.opens); assertEquals(2, ApkOs.closes)
    }
}
