package com.byd.dashcast.infrastructure.task;

import android.app.ActivityManager;
import android.content.Context;

import com.byd.dashcast.util.AppLogger;

import java.util.List;

/**
 * Path 1: {@code ActivityManager.getRunningTasks()}.
 *
 * On API 21+ this only returns the caller's own task for non-system apps,
 * so this succeeds only when DashCast itself holds GET_TASKS (legacy BYD ROMs).
 * Fast O(n) scan; kept as the head of the chain for those cases.
 */
public final class AmTaskFinder implements TaskFinder {

    private static final String TAG = "AmTaskFinder";

    private final Context mContext;

    public AmTaskFinder(Context context) {
        mContext = context.getApplicationContext();
    }

    @Override
    public int findTaskId(String packageName) throws TaskFinderException {
        try {
            ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return NOT_FOUND;
            @SuppressWarnings("deprecation")
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(50);
            if (tasks == null) return NOT_FOUND;
            for (ActivityManager.RunningTaskInfo t : tasks) {
                if (t.topActivity != null
                        && packageName.equals(t.topActivity.getPackageName())) {
                    AppLogger.d(TAG, packageName + " → taskId=" + t.id + " (AM)");
                    return t.id;
                }
            }
        } catch (Exception e) {
            throw new TaskFinderException("AM getRunningTasks failed: " + e.getMessage(), e);
        }
        return NOT_FOUND;
    }
}
