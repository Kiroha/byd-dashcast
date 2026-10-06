package com.byd.dashcast.update

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import com.byd.dashcast.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowApplicationPackageManager
import org.robolectric.shadows.ShadowSigningInfo
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [OtaApkValidationTest.Android10ArchivePackageManager::class])
class OtaApkValidationTest {
    private lateinit var context: Context
    private lateinit var apk: File
    private lateinit var asset: UpdateChecker.ReleaseAsset
    private lateinit var installed: PackageInfo

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        apk = File(context.cacheDir, "ota-validation-test.apk").apply {
            writeText("APK bytes handled by the certificate-collection fixture")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(apk.readBytes())
            .joinToString("") { "%02x".format(it) }
        asset = UpdateChecker.ReleaseAsset(
            version = "99.0.0", changelog = "", downloadUrl = "https://example.invalid/update.apk",
            name = "DashCast-v99.0.0-release.apk", size = apk.length(), sha256 = digest
        )
        installed = packageInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
        Shadows.shadowOf(context.packageManager).installPackage(installed)
        Android10ArchivePackageManager.archive = packageInfo(asset.version, BuildConfig.VERSION_CODE + 1L)
        Android10ArchivePackageManager.requests.clear()
    }

    @Test
    fun `Android 10 archive collection accepts a matching signer`() {
        validate()
    }

    @Test
    fun `a different signer is still rejected after certificate collection`() {
        Android10ArchivePackageManager.archive.signingInfo = signingInfo(Signature(byteArrayOf(9, 8, 7)))

        val error = assertThrows(Exception::class.java) { validate() }

        assertEquals("APK signer mismatch", error.message)
    }

    @Test
    fun `missing archive certificates remain rejected`() {
        Android10ArchivePackageManager.archive.signingInfo = null

        val error = assertThrows(Exception::class.java) { validate() }

        assertEquals("Downloaded APK signing certificates unavailable", error.message)
    }

    @Test
    fun `missing installed certificates remain rejected`() {
        installed.signingInfo = null
        Shadows.shadowOf(context.packageManager).installPackage(installed)

        val error = assertThrows(Exception::class.java) { validate() }

        assertEquals("Installed app signing certificates unavailable", error.message)
    }

    @Test
    fun `modified download is rejected before asking for certificates`() {
        apk.appendText("changed bytes")

        val error = assertThrows(Exception::class.java) { validate() }

        assertEquals("APK SHA-256 mismatch", error.message)
        assertTrue(Android10ArchivePackageManager.requests.isEmpty())
    }

    private fun validate() {
        val method = UpdateChecker::class.java.getDeclaredMethod(
            "validateDownloadedApk", Context::class.java, File::class.java,
            UpdateChecker.ReleaseAsset::class.java
        ).apply { isAccessible = true }
        try {
            method.invoke(UpdateChecker, context, apk, asset)
        } catch (wrapped: InvocationTargetException) {
            throw wrapped.cause!!
        }
    }

    private fun packageInfo(version: String, code: Long) = PackageInfo().apply {
        packageName = context.packageName
        versionName = version
        longVersionCode = code
        applicationInfo = ApplicationInfo().apply { packageName = context.packageName }
        signingInfo = signingInfo(Signature(byteArrayOf(1, 2, 3)))
    }

    private fun signingInfo(signature: Signature): SigningInfo =
        Shadow.newInstanceOf(SigningInfo::class.java).also {
            Shadow.extract<ShadowSigningInfo>(it).setSignatures(arrayOf(signature))
        }

    @Implements(className = "android.app.ApplicationPackageManager")
    class Android10ArchivePackageManager : ShadowApplicationPackageManager() {
        @Implementation
        @Suppress("DEPRECATION")
        public override fun getPackageArchiveInfo(archiveFilePath: String, flags: Int): PackageInfo {
            requests += flags
            // Android 10 / DiLink 3 getPackageArchiveInfo only calls collectCertificates
            // when GET_SIGNATURES is set, even if GET_SIGNING_CERTIFICATES was requested.
            return PackageInfo().apply {
                packageName = archive.packageName
                versionName = archive.versionName
                longVersionCode = archive.longVersionCode
                applicationInfo = archive.applicationInfo
                signingInfo = if (flags and PackageManager.GET_SIGNATURES != 0)
                    archive.signingInfo else null
            }
        }

        companion object {
            lateinit var archive: PackageInfo
            val requests = mutableListOf<Int>()
        }
    }
}
