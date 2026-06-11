package com.byd.dashcast.infrastructure.launch;

import android.app.ActivityOptions;
import android.content.Intent;

/**
 * Strategy interface for launching an app on a specific display.
 *
 * Two implementations with distinct privilege levels:
 *   - {@link IamAppLauncher}       — IActivityManager.startActivityAsUser + Context fallback
 *   - {@link ShellAppLauncher}     — DL5: {@code am start --display N --windowingMode 5}
 *   - {@link PlatformAdaptiveAppLauncher} — selects the right one at runtime
 */
public interface AppLauncher {

    /**
     * Launches {@code launchIntent} on {@code displayId} with the given options.
     *
     * @param packageName  package to launch (used for component resolution + logging)
     * @param displayId    target cluster display ID (> 0)
     * @param launchIntent ready-to-launch intent with component and flags already set
     * @param opts         ActivityOptions with display ID and FREEFORM windowing mode set
     * @throws LaunchException if the launch cannot be initiated
     */
    void launch(String packageName, int displayId, Intent launchIntent, ActivityOptions opts)
            throws LaunchException;

    class LaunchException extends Exception {
        public LaunchException(String message) { super(message); }
        public LaunchException(String message, Throwable cause) { super(message, cause); }
    }
}
