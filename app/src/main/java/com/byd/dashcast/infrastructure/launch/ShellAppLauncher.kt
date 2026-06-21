package com.byd.dashcast.infrastructure.launch

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent

import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.proxy.ShellGateway
import com.byd.dashcast.util.AppLogger

/**
 * DL5 launch path: `am force-stop <pkg> && am start --display N --windowingMode 5 -n <component>`.
 *
 * Rationale: on DiLink 5.0 (API 32) our process uid 10148 is denied cross-display launches
 * by IActivityTaskManager (SecurityException with launchDisplayId=3). The shell uid 2000
 * has the required privileges and is accessible via AdbLocalClient.
 *
 * `--windowingMode 5` (FREEFORM) is mandatory: without it the task lands as FULLSCREEN
 * and subsequent `cmd activity task resize` calls are silent no-ops on API 30+.
 */
class ShellAppLauncher(context: Context) : AppLauncher {

    private val mContext: Context = context.applicationContext

    @Throws(AppLauncher.LaunchException::class)
    override fun launch(packageName: String, displayId: Int, launchIntent: Intent?, opts: ActivityOptions) {
        val cn = launchIntent?.component
            ?: throw AppLauncher.LaunchException("Cannot resolve component for $packageName")
        val component = cn.packageName + "/" + cn.className
        val cmd = "am force-stop $packageName 2>&1; " +
            "am start --display $displayId" +
            " --windowingMode 5" +
            " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER" +
            " -n $component" +
            " --activity-clear-task 2>&1"
        AppLogger.i(TAG, "DL5 shell launch: $cmd")
        ShellGateway.execShellWithResult(mContext, cmd, object : AdbLocalClient.Callback {
            override fun onSuccess(out: String?) {
                AppLogger.i(TAG, "DL5 am start → " + (out?.trim() ?: ""))
            }

            override fun onError(err: String?) {
                AppLogger.e(TAG, "DL5 am start ERROR: $err")
            }
        })
        // Fire-and-forget: errors are logged but do not propagate (consistent with the original
        // behaviour where the launchOnDashboard callback optimistically reported success after dispatch).
    }

    companion object {
        private const val TAG = "ShellAppLauncher"
    }
}
