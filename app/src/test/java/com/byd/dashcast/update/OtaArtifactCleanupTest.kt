package com.byd.dashcast.update

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OtaArtifactCleanupTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `clearing terminal install state removes cache and external OTA sources`() {
        val cache = File(context.cacheDir, OtaArtifactCleanup.APK_CACHE_NAME).apply {
            writeText("cache apk")
        }
        val external = File(
            context.getExternalFilesDir(null), OtaArtifactCleanup.APK_CACHE_NAME
        ).apply { writeText("external apk") }
        OtaRelaunchCoordinator.markPending(context)

        OtaRelaunchCoordinator.clearPending(context)

        assertFalse(cache.exists())
        assertFalse(external.exists())
    }

    @Test
    fun `marking a pending install does not delete its source`() {
        val source = File(context.cacheDir, OtaArtifactCleanup.APK_CACHE_NAME).apply {
            writeText("pending apk")
        }

        OtaRelaunchCoordinator.markPending(context)

        assertTrue(source.exists())
        OtaRelaunchCoordinator.clearPending(context)
    }
}