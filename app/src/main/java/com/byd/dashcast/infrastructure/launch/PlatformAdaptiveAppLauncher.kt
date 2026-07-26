package com.byd.dashcast.infrastructure.launch

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent

import com.byd.dashcast.infrastructure.AdbLocalClient

/**
 * Selects the right launcher at runtime based on the detected BYD DiLink version.
 *
 * DL5 (API 32): SecurityException on cross-display IATM calls → [ShellAppLauncher].
 * DL2/3/4: IAM-based launch works → [IamAppLauncher].
 *
 * The selection is evaluated once per [launch] call; the Platform singleton
 * caches the result, so repeated calls are O(1) after the first.
 */
class PlatformAdaptiveAppLauncher(context: Context) : AppLauncher {

    private val mContext: Context = context.applicationContext
    private val mIamLauncher = IamAppLauncher(context)
    private val mShellLauncher = ShellAppLauncher(context)

    @Throws(AppLauncher.LaunchException::class)
    override fun launch(packageName: String, displayId: Int, launchIntent: Intent?, opts: ActivityOptions) {
        if (AdbLocalClient.isDiLink5Safe(mContext)) {
            mShellLauncher.launch(packageName, displayId, launchIntent, opts)
        } else {
            mIamLauncher.launch(packageName, displayId, launchIntent, opts)
        }
    }
}
