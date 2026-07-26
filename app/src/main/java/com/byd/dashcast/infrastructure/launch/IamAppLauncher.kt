package com.byd.dashcast.infrastructure.launch

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

import com.byd.dashcast.util.AppLogger

/**
 * Launches via `IActivityManager.startActivityAsUser()` (userId=-2 = current user).
 * Falls back to `Context.startActivity()` if the reflection call fails.
 *
 * This is the default path for DL2/3/4 where our uid is permitted to call
 * cross-display launches via IAM.
 */
class IamAppLauncher(context: Context) : AppLauncher {

    private val mContext: Context = context.applicationContext

    @Throws(AppLauncher.LaunchException::class)
    override fun launch(packageName: String, displayId: Int, launchIntent: Intent?, opts: ActivityOptions) {
        try {
            val amClass = Class.forName("android.app.ActivityManager")
            val iam = amClass.getMethod("getService").invoke(null)
            val iAmClass = Class.forName("android.app.IActivityManager")
            val iAppThreadClass = Class.forName("android.app.IApplicationThread")
            val profilerInfoClass = Class.forName("android.app.ProfilerInfo")
            // int.class — the primitive Class must match the reflective method signature.
            val intType = Int::class.javaPrimitiveType

            iAmClass.getMethod(
                "startActivityAsUser",
                iAppThreadClass, String::class.java, Intent::class.java,
                String::class.java, IBinder::class.java, String::class.java,
                intType, intType, profilerInfoClass,
                Bundle::class.java, intType
            ).invoke(
                iam,
                null, mContext.packageName, launchIntent,
                null, null, null,
                0, 0, null,
                opts.toBundle(), -2 /* USER_CURRENT */
            )
            AppLogger.i(TAG, "startActivityAsUser OK → $packageName display=$displayId")
        } catch (e: Exception) {
            AppLogger.w(TAG, "IAM failed, falling back to Context.startActivity: ${e.message}")
            try {
                mContext.startActivity(launchIntent, opts.toBundle())
                AppLogger.i(TAG, "Context.startActivity OK → $packageName")
            } catch (ex: Exception) {
                throw AppLauncher.LaunchException(
                    "IAM and Context.startActivity both failed for $packageName: ${ex.message}", ex
                )
            }
        }
    }

    companion object {
        private const val TAG = "IamAppLauncher"
    }
}
