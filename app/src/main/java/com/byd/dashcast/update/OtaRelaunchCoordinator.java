package com.byd.dashcast.update;

import android.content.Context;
import android.content.Intent;

import com.byd.dashcast.util.AppLogger;

/** Persists an OTA restart request across the package-replacement process kill. */
@android.annotation.SuppressLint("ApplySharedPref")
public final class OtaRelaunchCoordinator {
    private static final String TAG = "OtaRelaunch";
    private static final String PREFS = "dashcast_ota_state";
    private static final String KEY_REQUESTED_AT = "relaunch_requested_at";
    private static final Object LOCK = new Object();

    private OtaRelaunchCoordinator() {}

    /** Must reach disk before pm/PackageInstaller kills the current app process. */
    public static void markPending(Context context) {
        boolean saved = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_REQUESTED_AT, System.currentTimeMillis())
                .commit();
        AppLogger.i(TAG, "OTA relaunch marker saved=" + saved);
    }

    public static void clearPending(Context context) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_REQUESTED_AT)
                .commit();
    }

    public static boolean relaunchIfPending(Context context, String source) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            long requestedAt = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(KEY_REQUESTED_AT, 0L);
            if (!OtaRelaunchPolicy.shouldRelaunch(requestedAt, System.currentTimeMillis())) {
                if (requestedAt != 0L) clearPending(app);
                return false;
            }
            // Claim synchronously before launching so STATUS_SUCCESS and
            // MY_PACKAGE_REPLACED cannot both create an Activity task.
            boolean claimed = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEY_REQUESTED_AT)
                    .commit();
            if (!claimed) {
                AppLogger.w(TAG, "could not claim OTA relaunch marker (source=" + source + ")");
                return false;
            }
        }
        try {
            Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
            if (launch == null) {
                AppLogger.w(TAG, "no launcher intent after OTA (source=" + source + ")");
                markPending(app);
                return false;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            app.startActivity(launch);
            AppLogger.i(TAG, "DashCast relaunched after OTA via " + source);
            return true;
        } catch (Throwable error) {
            markPending(app);
            AppLogger.w(TAG, "OTA relaunch failed via " + source + ": " + error);
            return false;
        }
    }
}