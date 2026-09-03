package com.byd.dashcast.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent

import androidx.core.content.edit

import com.byd.dashcast.util.AppLogger

/** Persists an OTA restart request across the package-replacement process kill. */
@SuppressLint("ApplySharedPref")
object OtaRelaunchCoordinator {
    private const val TAG = "OtaRelaunch"
    private const val PREFS = "dashcast_ota_state"
    private const val KEY_REQUESTED_AT = "relaunch_requested_at"
    private val LOCK = Any()

    /** Must reach disk before pm/PackageInstaller kills the current app process. */
    @JvmStatic
    fun markPending(context: Context) {
        val saved = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_REQUESTED_AT, System.currentTimeMillis())
            .commit()
        AppLogger.i(TAG, "OTA relaunch marker saved=$saved")
    }

    @JvmStatic
    fun clearPending(context: Context) {
        // commit = true keeps the original synchronous .commit() semantics; the boolean
        // result is intentionally unused here (unlike markPending/relaunchIfPending, which
        // must keep the raw .commit() to read its return for the atomic claim).
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit(commit = true) { remove(KEY_REQUESTED_AT) }
        OtaArtifactCleanup.cleanup(context)
    }

    @JvmStatic
    fun relaunchIfPending(context: Context, source: String): Boolean {
        val app = context.applicationContext
        synchronized(LOCK) {
            val requestedAt = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_REQUESTED_AT, 0L)
            if (!OtaRelaunchPolicy.shouldRelaunch(requestedAt, System.currentTimeMillis())) {
                if (requestedAt != 0L) clearPending(app)
                return false
            }
            // Claim synchronously before launching so STATUS_SUCCESS and
            // MY_PACKAGE_REPLACED cannot both create an Activity task.
            val claimed = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_REQUESTED_AT)
                .commit()
            if (!claimed) {
                AppLogger.w(TAG, "could not claim OTA relaunch marker (source=$source)")
                return false
            }
        }
        // STATUS_SUCCESS / MY_PACKAGE_REPLACED proves the installer is finished. The staged
        // PackageInstaller data is independent; the downloaded source can no longer be read.
        OtaArtifactCleanup.cleanup(app)
        try {
            val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
            if (launch == null) {
                AppLogger.w(TAG, "no launcher intent after OTA (source=$source)")
                markPending(app)
                return false
            }
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            app.startActivity(launch)
            AppLogger.i(TAG, "DashCast relaunched after OTA via $source")
            return true
        } catch (error: Throwable) {
            markPending(app)
            AppLogger.w(TAG, "OTA relaunch failed via $source: $error")
            return false
        }
    }
}
