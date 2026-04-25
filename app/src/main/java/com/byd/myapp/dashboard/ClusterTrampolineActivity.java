package com.byd.myapp.dashboard;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;

import com.byd.myapp.AppLogger;

/**
 * ClusterTrampolineActivity — empty activity launched on display 1 to serve as
 * "source activity" when launching third-party apps on the cluster.
 *
 * Why: on Seal EU (DiLink 3.0, Android 10), pushing a third-party app
 * directly with ActivityOptions.setLaunchDisplayId(1) fails with
 * SecurityException in SafeActivityOptions.checkPermissions() — even when
 * the APK is signed with platform.keystore (INTERNAL_SYSTEM_WINDOW and MANAGE_ACTIVITY_STACKS
 * are declared but the ROM does not consider them sufficient to target a non-default display
 * belonging to another process — confirmed in car with v1.69 and v1.70).
 *
 * AOSP API 29 — ActivityStackSupervisor.isCallerAllowedToLaunchOnDisplay():
 *   final int targetUid = aInfo.applicationInfo.uid;
 *   if (targetUid == callingUid) return true;   // ← we pass here
 *
 * So launching OUR OWN activity (same uid) with setLaunchDisplayId(1) is
 * allowed. Once this activity is on display 1, we call
 * `Activity.startActivity(intent_third_party)` WITHOUT setLaunchDisplayId: ActivityStarter
 * places the new task on the source display (display 1) — no SafeActivityOptions
 * check is triggered because launchDisplayId == INVALID_DISPLAY.
 *
 * This is exactly the pattern used by the official BYDDashboard app.
 *
 * Lifecycle: finish() is called immediately after starting the target app. The task
 * remains on display 1 even after the trampoline is destroyed.
 */
public class ClusterTrampolineActivity extends Activity {

    private static final String TAG = "ClusterTrampoline";
    public static final String EXTRA_TARGET_PACKAGE = "target_package";

    /** Builds the launch Intent for this trampoline targeting a given package. */
    public static Intent buildLaunchIntent(Context ctx, String targetPackage) {
        Intent i = new Intent(ctx, ClusterTrampolineActivity.class);
        i.putExtra(EXTRA_TARGET_PACKAGE, targetPackage);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLogger.lifecycle(getClass().getSimpleName(), "onCreate");

        String pkg = getIntent() != null
                ? getIntent().getStringExtra(EXTRA_TARGET_PACKAGE)
                : null;

        AppLogger.i(TAG, "Trampoline launched on display="
                + getWindowManager().getDefaultDisplay().getDisplayId()
                + " — target: " + pkg);

        if (pkg == null || pkg.isEmpty()) {
            AppLogger.w(TAG, "No target package — finish");
            finish();
            return;
        }

        Intent launch = resolveLaunchIntent(pkg);
        if (launch == null) {
            AppLogger.e(TAG, "No Activity found for " + pkg);
            finish();
            return;
        }

        // CRUCIAL: no setLaunchDisplayId here. The new task inherits the
        // display of the source activity (us → display 1).
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);

        // Optional bounds passed via integer extras (DiLink 3.0 does not accept --bounds)
        int bl = getIntent().getIntExtra("bounds_l", -1);
        int bt = getIntent().getIntExtra("bounds_t", -1);
        int br = getIntent().getIntExtra("bounds_r", -1);
        int bb = getIntent().getIntExtra("bounds_b", -1);
        boolean hasBounds = bl >= 0 && br > bl && bb > bt;

        try {
            if (hasBounds) {
                ActivityOptions opts = ActivityOptions.makeBasic();
                opts.setLaunchBounds(new Rect(bl, bt, br, bb));
                try {
                    // For Android 10 (API 29), setLaunchWindowingMode(5) WINDOWING_MODE_FREEFORM
                    java.lang.reflect.Method setLaunchWm = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
                    setLaunchWm.invoke(opts, 5);
                } catch (Exception ignored) {}
                
                startActivity(launch, opts.toBundle());
                AppLogger.i(TAG, "startActivity(" + pkg + ") bounds=["
                        + bl + "," + bt + "," + br + "," + bb + "] OK depuis trampoline");
            } else {
                startActivity(launch);
                AppLogger.i(TAG, "startActivity(" + pkg + ") OK depuis trampoline");
            }
        } catch (Exception e) {
            if (hasBounds) {
                // Fallback without bounds if setLaunchBounds is not supported on this ROM
                AppLogger.w(TAG, "startActivity with bounds failed (" + e.getMessage()
                        + "), fallback sans bounds");
                try {
                    startActivity(launch);
                    AppLogger.i(TAG, "startActivity(" + pkg + ") fallback sans bounds OK");
                } catch (Exception e2) {
                    AppLogger.e(TAG, "startActivity fallback also failed for " + pkg, e2);
                }
            } else {
                AppLogger.e(TAG, "startActivity failed for " + pkg, e);
            }
        }
        finish();
    }

    private Intent resolveLaunchIntent(String packageName) {
        PackageManager pm = getPackageManager();
        Intent li = pm.getLaunchIntentForPackage(packageName);
        if (li != null) return li;

        try {
            PackageInfo pi = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
            if (pi.activities != null && pi.activities.length > 0) {
                ActivityInfo ai = pi.activities[0];
                Intent i = new Intent();
                i.setComponent(new ComponentName(packageName, ai.name));
                return i;
            }
        } catch (PackageManager.NameNotFoundException ignored) {}
        return null;
    }
}
