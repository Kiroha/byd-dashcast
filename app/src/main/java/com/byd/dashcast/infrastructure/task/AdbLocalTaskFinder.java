package com.byd.dashcast.infrastructure.task;

import android.content.Context;

import com.byd.dashcast.infrastructure.AdbLocalClient;
import com.byd.dashcast.util.AppLogger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Paths 3 + 3b: AdbLocalClient shell fallback.
 *
 * Used when the proxy daemon is not running. Blocks with a {@link CountDownLatch}
 * (5 s per attempt). Both {@code dumpsys activity recents} and
 * {@code dumpsys activity activities} are tried before giving up.
 *
 * <b>Threading:</b> must be called from a background thread — blocks up to 10 s total.
 */
public final class AdbLocalTaskFinder implements TaskFinder {

    private static final String TAG = "AdbLocalTaskFinder";
    private static final long TIMEOUT_SEC = 5;

    private final Context mContext;

    public AdbLocalTaskFinder(Context context) {
        mContext = context.getApplicationContext();
    }

    @Override
    public int findTaskId(String packageName) throws TaskFinderException {
        // Path 3 — AdbLocal dumpsys activity recents
        String recents = runShellBlocking("dumpsys activity recents", packageName + " recents");
        if (recents != null && !recents.isEmpty()) {
            int id = ProxyTaskFinder.parseFromRecents(recents, packageName);
            if (id != NOT_FOUND) {
                AppLogger.d(TAG, packageName + " → " + id + " (AdbLocal recents)");
                return id;
            }
        }
        // Path 3b — AdbLocal dumpsys activity activities
        String activities = runShellBlocking("dumpsys activity activities", packageName + " activities");
        if (activities != null && !activities.isEmpty()) {
            int id = ProxyTaskFinder.parseFromActivities(activities, packageName);
            if (id != NOT_FOUND) {
                AppLogger.d(TAG, packageName + " → " + id + " (AdbLocal activities)");
                return id;
            }
        }
        return NOT_FOUND;
    }

    private String runShellBlocking(String cmd, String label) throws TaskFinderException {
        AtomicReference<String> outRef = new AtomicReference<>();
        AtomicReference<String> errRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        AdbLocalClient.executeShellWithResult(mContext, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String out) { outRef.set(out); latch.countDown(); }
            @Override public void onError(String err)   { errRef.set(err);  latch.countDown(); }
        });

        try {
            if (!latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                throw new TaskFinderException("AdbLocal timeout (" + TIMEOUT_SEC + "s) for " + label);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskFinderException("AdbLocal interrupted for " + label, e);
        }

        String err = errRef.get();
        if (err != null) {
            AppLogger.w(TAG, "AdbLocal error for " + label + ": " + err);
            return null;
        }
        return outRef.get();
    }
}
