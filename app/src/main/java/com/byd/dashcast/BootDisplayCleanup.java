package com.byd.dashcast;

import android.content.Context;
import android.content.ComponentName;

import com.byd.dashcast.data.prefs.ClusterPrefs;

/**
 * Boot/onCreate safety net: moves cluster-affined apps back to Display 0
 * using IActivityTaskManager reflection (no ClusterService needed).
 * Only runs when boot_auto_start_enabled is false.
 */
public final class BootDisplayCleanup {

    private static final String TAG = "BootDisplayCleanup";

    private BootDisplayCleanup() {}

    public static void cleanup(Context context) {
        java.util.Set<String> pkgs = ClusterPrefs.getSessionClusterPkgs(context);
        if (pkgs == null || pkgs.isEmpty()) {
            AppLogger.d(TAG, "No session cluster packages to clean up");
            return;
        }
        java.util.Set<String> remaining = new java.util.HashSet<>(pkgs);
        AppLogger.i(TAG, "Cleaning up " + pkgs.size() + " apps → Display 0: " + pkgs);
        for (String pkg : pkgs) {
            if (moveTaskToDisplayZero(pkg)) {
                remaining.remove(pkg);
            }
        }
        if (remaining.isEmpty()) {
            ClusterPrefs.clearSessionClusterPkgs(context);
        } else {
            ClusterPrefs.setSessionClusterPkgs(context, remaining);
            AppLogger.w(TAG, "Cleanup partially failed, keeping pending set: " + remaining);
        }
    }

    private static boolean moveTaskToDisplayZero(String packageName) {
        try {
            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            Object iatm = atmClass.getMethod("getService").invoke(null);
            @SuppressWarnings("unchecked")
            java.util.List<?> tasks = (java.util.List<?>) iatm.getClass()
                    .getMethod("getTasks", int.class).invoke(iatm, 100);
            if (tasks == null) return false;
            for (Object taskInfo : tasks) {
                ComponentName base = (ComponentName)
                        taskInfo.getClass().getField("baseActivity").get(taskInfo);
                if (base != null && packageName.equals(base.getPackageName())) {
                    int taskId = taskInfo.getClass().getField("taskId").getInt(taskInfo);
                    iatm.getClass().getMethod("moveTaskToDisplay", int.class, int.class)
                            .invoke(iatm, taskId, 0);
                    AppLogger.i(TAG, "Moved " + packageName
                            + " (taskId=" + taskId + ") → Display 0");
                    return true;
                }
            }
            AppLogger.d(TAG, "No running task found for " + packageName + " — already gone, skipping");
            return true;
        } catch (Exception e) {
            AppLogger.w(TAG, "Could not move " + packageName + " to Display 0: " + e.getMessage());
            return false;
        }
    }
}
