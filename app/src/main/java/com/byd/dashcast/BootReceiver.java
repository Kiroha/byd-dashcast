package com.byd.dashcast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String PREFS_NAME = "DashCastPrefs";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean bootEnabled = prefs.getBoolean("boot_overscan_enabled", false);
            
            if (bootEnabled) {
                int left = prefs.getInt("overscan_left", 0);
                int top = prefs.getInt("overscan_top", 0);
                int right = prefs.getInt("overscan_right", 0);
                int bottom = prefs.getInt("overscan_bottom", 0);
                
                String cmd = String.format("wm overscan %d,%d,%d,%d -d 1", left, top, right, bottom);
                Log.d("BootReceiver", "DashCast Auto-Boot Overscan triggered: " + cmd);
                
                try {
                    // Start process as standard Android shell does for adb-like permissions
                    // Since it relies on BYD system specifics, this process will restore the user config
                    Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                    p.waitFor();
                } catch (Exception e) {
                    Log.e("BootReceiver", "Error applying overscan on boot: " + e.getMessage());
                }
            } else {
                Log.d("BootReceiver", "DashCast Auto-Boot Overscan is disabled by user.");
            }
        }
    }
}
