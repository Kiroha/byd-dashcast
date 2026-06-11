package com.byd.dashcast.infrastructure.launch;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;

import com.byd.dashcast.AdbLocalClient;

/**
 * Selects the right launcher at runtime based on the detected BYD DiLink version.
 *
 * DL5 (API 32): SecurityException on cross-display IATM calls → {@link ShellAppLauncher}.
 * DL2/3/4: IAM-based launch works → {@link IamAppLauncher}.
 *
 * The selection is evaluated once per {@link #launch} call; the Platform singleton
 * caches the result, so repeated calls are O(1) after the first.
 */
public final class PlatformAdaptiveAppLauncher implements AppLauncher {

    private final IamAppLauncher   mIamLauncher;
    private final ShellAppLauncher mShellLauncher;
    private final Context          mContext;

    public PlatformAdaptiveAppLauncher(Context context) {
        mContext       = context.getApplicationContext();
        mIamLauncher   = new IamAppLauncher(context);
        mShellLauncher = new ShellAppLauncher(context);
    }

    @Override
    public void launch(String packageName, int displayId, Intent launchIntent,
                       ActivityOptions opts) throws LaunchException {
        if (AdbLocalClient.isDiLink5Safe(mContext)) {
            mShellLauncher.launch(packageName, displayId, launchIntent, opts);
        } else {
            mIamLauncher.launch(packageName, displayId, launchIntent, opts);
        }
    }
}
