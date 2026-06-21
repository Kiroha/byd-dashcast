package com.byd.dashcast.infrastructure.launch

import android.app.ActivityOptions
import android.content.Intent

/**
 * Strategy interface for launching an app on a specific display.
 *
 * Two implementations with distinct privilege levels:
 *   - [IamAppLauncher]              — IActivityManager.startActivityAsUser + Context fallback
 *   - [ShellAppLauncher]            — DL5: `am start --display N --windowingMode 5`
 *   - [PlatformAdaptiveAppLauncher] — selects the right one at runtime
 */
interface AppLauncher {

    /**
     * Launches [launchIntent] on [displayId] with the given options.
     *
     * @param packageName  package to launch (used for component resolution + logging)
     * @param displayId    target cluster display ID (> 0)
     * @param launchIntent ready-to-launch intent with component and flags already set
     * @param opts         ActivityOptions with display ID and FREEFORM windowing mode set
     * @throws LaunchException if the launch cannot be initiated
     */
    @Throws(LaunchException::class)
    fun launch(packageName: String, displayId: Int, launchIntent: Intent?, opts: ActivityOptions)

    class LaunchException : Exception {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }
}
