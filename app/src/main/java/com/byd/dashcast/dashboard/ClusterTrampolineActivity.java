package com.byd.dashcast.dashboard;

import android.app.Activity;
import android.os.Bundle;

import com.byd.dashcast.AppLogger;

/**
 * NEUTRALIZED — kept only to avoid manifest errors.
 * This activity used to launch com.byd.appstartmanagement as an attempt to spawn the cluster
 * VirtualDisplay natively, but that approach is broken and dangerous (resets the cluster display).
 * Nobody calls this activity anymore; it is exported=false in the manifest.
 */
public class ClusterTrampolineActivity extends Activity {

    private static final String TAG = "ClusterTrampolineActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLogger.w(TAG, "ClusterTrampolineActivity reached — this activity is neutralized, finishing immediately.");
        finish();
    }
}
