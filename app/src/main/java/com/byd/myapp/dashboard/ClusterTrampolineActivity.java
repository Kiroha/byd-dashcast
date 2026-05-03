package com.byd.myapp.dashboard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.byd.myapp.AppLogger;

public class ClusterTrampolineActivity extends Activity {

    private static final String TAG = "ClusterTrampolineActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        AppLogger.i(TAG, "ClusterTrampolineActivity onCreate - bypassing Freedom to spawn virtual display directly");

        try {
            // Trigger com.byd.appstartmanagement to spawn the VirtualDisplay natively on the BYD cluster
            Intent launchIntent = new Intent();
            launchIntent.setClassName("com.byd.appstartmanagement", "com.byd.appstartmanagement.frame.AppStartManagement");
            // Flag 268468224 is equivalent to 0x10008000 = FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK
            launchIntent.addFlags(268468224);
            startActivity(launchIntent);
            AppLogger.i(TAG, "Successfully invoked com.byd.appstartmanagement!");
        } catch (Exception e) {
            AppLogger.e(TAG, "Error invoking appstartmanagement", e);
        }

        // Finish the trampoline so it doesn't linger
        finish();
    }
}
