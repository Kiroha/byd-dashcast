package com.byd.dashcast.proxy.daemon

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Process
import com.byd.dashcast.BuildConfig
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowApplicationPackageManager
import org.robolectric.shadows.ShadowSigningInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the actual daemon entry gates, including a same-build reinstall's changed UID. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29, 33])
class DaemonInstalledIdentityTest {
    @After fun reset() {
        for (daemon in listOf(ProxyDaemonMain, SurfaceDaemon)) {
            field(daemon, "sCallerIdentity").set(daemon, null)
        }
        ArchivePackageManager.paths.clear()
        ArchivePackageManager.failures = 0
    }

    @Test fun `both daemon gates authorize the installed uid after a same build uid change`() {
        for (daemon in listOf(ProxyDaemonMain, SurfaceDaemon)) {
            install(10125)
            prepare(daemon)
            assertTrue(allowed(daemon, 10125))
            install(10127)
            assertTrue("${daemon.javaClass.simpleName} retained obsolete uid 10125", allowed(daemon, 10127))
            assertFalse(allowed(daemon, 10125))
        }
    }

    @Test fun `both daemon gates revoke the obsolete uid even when another package inherits it`() {
        for (daemon in listOf(ProxyDaemonMain, SurfaceDaemon)) {
            install(10125)
            prepare(daemon)
            assertTrue(allowed(daemon, 10125))
            install(10127)
            val reassigned = packageInfo(10125).apply {
                packageName = "com.example.reassigned"
                applicationInfo!!.packageName = packageName
            }
            shadowOf(RuntimeEnvironment.getApplication().packageManager).installPackage(reassigned)
            assertFalse("${daemon.javaClass.simpleName} accepted reassigned uid 10125", allowed(daemon, 10125))
        }
    }

    @Test fun `both daemon gates fail closed for unknown signing metadata and recover with fresh PM data`() {
        install(10127, signed = false)
        for (daemon in listOf(ProxyDaemonMain, SurfaceDaemon)) {
            prepare(daemon)
            assertFalse(allowed(daemon, 10127))
            install(10127)
            assertTrue(allowed(daemon, 10127))
            install(10127, signed = false)
        }
    }

    @Test fun `package name alone never authorizes a replacement signed by another key`() {
        install(10127, signer = byteArrayOf(9, 8, 7))
        for (daemon in listOf(ProxyDaemonMain, SurfaceDaemon)) {
            prepare(daemon)
            assertFalse(allowed(daemon, 10127))
            assertFalse(allowed(daemon, 10125))
        }
    }

    @Test fun `unknown caller is denied and legacy same app id and trusted identities are preserved`() {
        install(10127)
        for (daemon in listOf(ProxyDaemonMain, SurfaceDaemon)) {
            prepare(daemon)
            assertFalse(allowed(daemon, 10128))
            assertFalse(allowed(daemon, -1))
            assertTrue(allowed(daemon, 110127))
            assertTrue(allowed(daemon, 1000))
            assertTrue(allowed(daemon, Process.myUid()))
        }
    }

    @Test fun `PM errors cannot reuse a previously successful identity`() {
        var unavailable = false
        val gate = DaemonCallerIdentity(2000, BuildConfig.APPLICATION_ID, listOf(TRUSTED)) {
            if (unavailable) throw PackageManager.NameNotFoundException()
            packageInfo(10127)
        }
        assertTrue(gate.isAllowed(10127))
        unavailable = true
        assertFalse(gate.isAllowed(10127))
        assertFalse(gate.isAllowed(10125))
        assertTrue(gate.isAllowed(1000))
        assertTrue(gate.isAllowed(2000))
        assertFalse(gate.isAllowed(0))
        unavailable = false
        assertTrue(gate.isAllowed(10127))
    }

    @Test fun `a late PM snapshot cannot overwrite the current installed identity`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val installed = AtomicReference(packageInfo(10125))
        val gate = DaemonCallerIdentity(2000, BuildConfig.APPLICATION_ID, listOf(TRUSTED)) {
            val snapshot = installed.get()
            if (Thread.currentThread().name == "old-pm-snapshot") {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            }
            snapshot
        }
        val previous = Thread({ gate.isAllowed(10125) }, "old-pm-snapshot")
        previous.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        installed.set(packageInfo(10127))
        assertTrue(gate.isAllowed(10127))
        release.countDown()
        previous.join(1000)
        assertFalse(gate.isAllowed(10125))
        assertTrue(gate.isAllowed(10127))
    }

    @Test fun `missing loaded signer anchor never grants an app identity`() {
        var calls = 0
        val gate = DaemonCallerIdentity(2000, BuildConfig.APPLICATION_ID, emptyList()) {
            calls++
            packageInfo(10127)
        }
        assertFalse(gate.isAllowed(10127))
        assertFalse(gate.isAllowed(10125))
        assertEquals(2, calls)
        assertTrue(gate.isAllowed(2000))
        assertEquals(2, calls)
    }

    @Test
    @Config(shadows = [ArchivePackageManager::class])
    fun `factory anchors the actual loaded APK once including Android 10 certificate flags`() {
        val app = RuntimeEnvironment.getApplication()
        ArchivePackageManager.archive = packageInfo(10125)
        install(10127)
        val gate = DaemonCallerIdentity.fromLoadedApk(app, "/data/app/loaded-dashcast/base.apk")!!
        assertTrue(gate.isAllowed(10127))
        assertFalse(gate.isAllowed(10125))
        assertEquals(listOf("/data/app/loaded-dashcast/base.apk"), ArchivePackageManager.paths)
        // Current PM metadata cannot replace the daemon's signer trust anchor.
        install(10127, signer = byteArrayOf(8, 9))
        assertFalse(gate.isAllowed(10127))
        assertEquals(1, ArchivePackageManager.paths.size)
    }

    @Test
    @Config(shadows = [ArchivePackageManager::class])
    fun `factory retries temporary archive metadata failure and refuses an unresolved anchor`() {
        val app = RuntimeEnvironment.getApplication()
        ArchivePackageManager.archive = packageInfo(10125)
        ArchivePackageManager.failures = 1
        assertTrue(DaemonCallerIdentity.fromLoadedApk(app, "/data/app/loaded/base.apk") != null)
        assertEquals(2, ArchivePackageManager.paths.size)
        ArchivePackageManager.paths.clear()
        ArchivePackageManager.archive = packageInfo(10125, signed = false)
        assertEquals(null, DaemonCallerIdentity.fromLoadedApk(app, "/data/app/loaded/base.apk"))
        assertEquals(3, ArchivePackageManager.paths.size)
        ArchivePackageManager.paths.clear()
        assertEquals(null, DaemonCallerIdentity.fromLoadedApk(app, "/one.apk:/other.apk"))
        assertTrue(ArchivePackageManager.paths.isEmpty())
    }

    @Implements(className = "android.app.ApplicationPackageManager")
    class ArchivePackageManager : ShadowApplicationPackageManager() {
        @Implementation
        @Suppress("DEPRECATION")
        public override fun getPackageArchiveInfo(archiveFilePath: String, flags: Int): PackageInfo {
            paths += archiveFilePath
            if (failures-- > 0) throw IllegalStateException("PM not ready")
            return PackageInfo().apply {
                packageName = archive.packageName
                applicationInfo = archive.applicationInfo
                signingInfo = if (flags and PackageManager.GET_SIGNATURES != 0 &&
                    flags and PackageManager.GET_SIGNING_CERTIFICATES != 0) archive.signingInfo else null
            }
        }
        companion object {
            lateinit var archive: PackageInfo
            var failures = 0
            val paths = mutableListOf<String>()
        }
    }

    private fun install(uid: Int, signed: Boolean = true, signer: ByteArray = TRUSTED) {
        shadowOf(RuntimeEnvironment.getApplication().packageManager).installPackage(packageInfo(uid, signed, signer))
    }

    private fun packageInfo(uid: Int, signed: Boolean = true, signer: ByteArray = TRUSTED): PackageInfo =
        PackageInfo().apply {
            packageName = BuildConfig.APPLICATION_ID
            applicationInfo = ApplicationInfo().apply { this.uid = uid; packageName = BuildConfig.APPLICATION_ID }
            if (signed) signingInfo = Shadow.newInstanceOf(SigningInfo::class.java).also {
                Shadow.extract<ShadowSigningInfo>(it).setSignatures(arrayOf(Signature(signer)))
            }
        }

    private fun prepare(daemon: Any) {
        val pm = RuntimeEnvironment.getApplication().packageManager
        field(daemon, "sCallerIdentity").set(daemon,
            DaemonCallerIdentity(Process.myUid(), BuildConfig.APPLICATION_ID, listOf(TRUSTED)) {
                pm.getPackageInfo(BuildConfig.APPLICATION_ID, PackageManager.GET_SIGNING_CERTIFICATES)
            })
    }

    private fun allowed(daemon: Any, uid: Int): Boolean = daemon.javaClass
        .getDeclaredMethod("isAllowedCaller", Int::class.javaPrimitiveType).apply { isAccessible = true }
        .invoke(daemon, uid) as Boolean

    private fun field(daemon: Any, name: String) = daemon.javaClass.getDeclaredField(name).apply { isAccessible = true }
    companion object { private val TRUSTED = byteArrayOf(1, 2, 3) }
}
