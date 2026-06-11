package com.byd.dashcast.infrastructure.launch;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;

import com.byd.dashcast.AppLogger;

/**
 * Launches via {@code IActivityManager.startActivityAsUser()} (userId=-2 = current user).
 * Falls back to {@code Context.startActivity()} if the reflection call fails.
 *
 * This is the default path for DL2/3/4 where our uid is permitted to call
 * cross-display launches via IAM.
 */
public final class IamAppLauncher implements AppLauncher {

    private static final String TAG = "IamAppLauncher";

    private final Context mContext;

    public IamAppLauncher(Context context) {
        mContext = context.getApplicationContext();
    }

    @Override
    public void launch(String packageName, int displayId, Intent launchIntent,
                       ActivityOptions opts) throws LaunchException {
        try {
            Class<?> amClass        = Class.forName("android.app.ActivityManager");
            Object   iam            = amClass.getMethod("getService").invoke(null);
            Class<?> iAmClass       = Class.forName("android.app.IActivityManager");
            Class<?> iAppThreadClass  = Class.forName("android.app.IApplicationThread");
            Class<?> profilerInfoClass = Class.forName("android.app.ProfilerInfo");

            iAmClass.getMethod("startActivityAsUser",
                    iAppThreadClass, String.class, Intent.class,
                    String.class, android.os.IBinder.class, String.class,
                    int.class, int.class, profilerInfoClass,
                    android.os.Bundle.class, int.class)
                    .invoke(iam,
                            null, mContext.getPackageName(), launchIntent,
                            null, null, null,
                            0, 0, null,
                            opts.toBundle(), -2 /* USER_CURRENT */);
            AppLogger.i(TAG, "startActivityAsUser OK → " + packageName + " display=" + displayId);
        } catch (Exception e) {
            AppLogger.w(TAG, "IAM failed, falling back to Context.startActivity: " + e.getMessage());
            try {
                mContext.startActivity(launchIntent, opts.toBundle());
                AppLogger.i(TAG, "Context.startActivity OK → " + packageName);
            } catch (Exception ex) {
                throw new LaunchException("IAM and Context.startActivity both failed for "
                        + packageName + ": " + ex.getMessage(), ex);
            }
        }
    }
}
