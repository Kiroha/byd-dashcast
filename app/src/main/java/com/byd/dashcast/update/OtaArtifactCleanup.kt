package com.byd.dashcast.update

import android.content.Context
import com.byd.dashcast.util.AppLogger
import java.io.File

/** Removes the single OTA source APK once no installer session can still need it. */
object OtaArtifactCleanup {
    const val APK_CACHE_NAME = "dashcast-update.apk"
    private const val TAG = "OtaArtifactCleanup"

    @JvmStatic
    fun cleanup(context: Context): Int {
        val app = context.applicationContext
        val candidates = linkedSetOf<File>()
        try {
            app.getExternalFilesDir(null)?.let { candidates += File(it, APK_CACHE_NAME) }
        } catch (_: Throwable) {
            candidates += File(
                "/storage/emulated/0/Android/data/${app.packageName}/files",
                APK_CACHE_NAME,
            )
        }
        candidates += File(app.cacheDir, APK_CACHE_NAME)
        var deleted = 0
        for (file in candidates) {
            try {
                if (file.isFile && file.delete()) deleted++
            } catch (error: Throwable) {
                AppLogger.w(TAG, "could not delete ${file.absolutePath}: ${error.message}")
            }
        }
        if (deleted > 0) AppLogger.i(TAG, "removed $deleted completed OTA source file(s)")
        return deleted
    }
}