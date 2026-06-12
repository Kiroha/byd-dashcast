package com.byd.dashcast.infrastructure.launch;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.byd.dashcast.infrastructure.AdbLocalClient;
import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.proxy.ShellGateway;

/**
 * DL5 launch path: {@code am force-stop <pkg> && am start --display N --windowingMode 5 -n <component>}.
 *
 * Rationale: on DiLink 5.0 (API 32) our process uid 10148 is denied cross-display launches
 * by IActivityTaskManager (SecurityException with launchDisplayId=3). The shell uid 2000
 * has the required privileges and is accessible via AdbLocalClient.
 *
 * {@code --windowingMode 5} (FREEFORM) is mandatory: without it the task lands as FULLSCREEN
 * and subsequent {@code cmd activity task resize} calls are silent no-ops on API 30+.
 */
public final class ShellAppLauncher implements AppLauncher {

    private static final String TAG = "ShellAppLauncher";

    private final Context mContext;

    public ShellAppLauncher(Context context) {
        mContext = context.getApplicationContext();
    }

    @Override
    public void launch(String packageName, int displayId, Intent launchIntent,
                       ActivityOptions opts) throws LaunchException {
        ComponentName cn = (launchIntent != null) ? launchIntent.getComponent() : null;
        if (cn == null) {
            throw new LaunchException("Cannot resolve component for " + packageName);
        }
        String component = cn.getPackageName() + "/" + cn.getClassName();
        String cmd = "am force-stop " + packageName + " 2>&1; "
                + "am start --display " + displayId
                + " --windowingMode 5"
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                + " -n " + component
                + " --activity-clear-task 2>&1";
        AppLogger.i(TAG, "DL5 shell launch: " + cmd);
        ShellGateway.execShellWithResult(mContext, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String out) {
                AppLogger.i(TAG, "DL5 am start → " + (out == null ? "" : out.trim()));
            }
            @Override public void onError(String err) {
                AppLogger.e(TAG, "DL5 am start ERROR: " + err);
            }
        });
        // Fire-and-forget: errors are logged but do not propagate (consistent with the original
        // behaviour where the launchOnDashboard callback optimistically reported success after dispatch).
    }
}
