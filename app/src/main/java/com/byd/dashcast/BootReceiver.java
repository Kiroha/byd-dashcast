package com.byd.dashcast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {

    private static final String PREFS_NAME = SettingsActivity.PREFS_NAME;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean autoStartEnabled = prefs.getBoolean("boot_auto_start_enabled", false);
            
            if (autoStartEnabled) {
                AppLogger.i("BootReceiver", "DashCast Auto-Boot: Starting projection automatically...");
                
                // Démarrer automatiquement le service de projection (ClusterService)
                try {
                    Intent serviceIntent = new Intent(context, ClusterService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                } catch (Exception e) {
                    AppLogger.e("BootReceiver", "Error starting ClusterService on boot: " + e.getMessage());
                }
            } else {
                AppLogger.d("BootReceiver", "DashCast Auto-Boot Projection is disabled by user.");
                // Projection not auto-started: move any apps that were on the cluster
                // (from the previous session) back to Display 0 so they don't get stuck
                // on the (possibly still-alive) VirtualDisplay.
                new Thread(() -> {
                    try {
                        MainActivity.cleanupDisplayAffinityAtBoot(context);
                    } catch (Exception e) {
                        AppLogger.e("BootReceiver", "Display cleanup error: " + e.getMessage());
                    }
                }, "boot-display-cleanup").start();
            }
        }
    }
}
