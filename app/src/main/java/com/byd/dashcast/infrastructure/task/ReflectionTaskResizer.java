package com.byd.dashcast.infrastructure.task;

import android.graphics.Rect;

import com.byd.dashcast.AppLogger;

import java.lang.reflect.InvocationTargetException;

/**
 * Resizes a task via {@code IActivityTaskManager.resizeTask()}.
 *
 * Tries the 3-arg form (Android 11/12: taskId, Rect, resizeMode) first,
 * then the 2-arg form (older vendor ROMs). Both arms surface the real cause
 * from {@link InvocationTargetException} so the caller's error logs are useful.
 *
 * Throws {@link ResizeException} if both signatures are absent or fail, so
 * {@link ChainedTaskResizer} can cascade to the shell-based fallback.
 */
public final class ReflectionTaskResizer implements TaskResizer {

    private static final String TAG = "ReflectionTaskResizer";

    private static final int RESIZE_MODE_FORCED = 1;

    @Override
    public void resize(int taskId, String packageName, Rect bounds) throws ResizeException {
        try {
            Class<?> iAtmClass = Class.forName("android.app.IActivityTaskManager");
            Object iatm;
            try {
                iatm = Class.forName("android.app.ActivityTaskManager")
                        .getMethod("getService").invoke(null);
            } catch (Exception e) {
                iatm = Class.forName("android.app.ActivityManager")
                        .getMethod("getService").invoke(null);
            }

            Throwable lastError = null;
            boolean done = false;

            // 3-arg form (preferred — AOSP API 30+)
            try {
                iAtmClass.getMethod("resizeTask", int.class, Rect.class, int.class)
                        .invoke(iatm, taskId, bounds, RESIZE_MODE_FORCED);
                done = true;
            } catch (InvocationTargetException ite) {
                lastError = ite.getTargetException() != null ? ite.getTargetException() : ite;
            } catch (NoSuchMethodException nsme) {
                lastError = nsme;
            } catch (Throwable t) {
                lastError = t;
            }

            // 2-arg form (older / vendor variants)
            if (!done) {
                try {
                    iAtmClass.getMethod("resizeTask", int.class, Rect.class)
                            .invoke(iatm, taskId, bounds);
                    done = true;
                    lastError = null;
                } catch (InvocationTargetException ite) {
                    if (lastError == null)
                        lastError = ite.getTargetException() != null ? ite.getTargetException() : ite;
                } catch (NoSuchMethodException ignored) {
                    // keep first error
                } catch (Throwable t) {
                    if (lastError == null) lastError = t;
                }
            }

            if (done) {
                AppLogger.i(TAG, "resizeTask " + packageName + " " + bounds + " OK");
                return;
            }

            String detail = (lastError == null) ? "unknown"
                    : lastError.getClass().getSimpleName() + ": " + lastError.getMessage();
            throw new ResizeException("reflection failed for taskId=" + taskId
                    + " pkg=" + packageName + ": " + detail,
                    lastError instanceof Exception ? (Exception) lastError : null);

        } catch (ResizeException re) {
            throw re;
        } catch (Exception e) {
            throw new ResizeException("outer reflection error: " + e.getMessage(), e);
        }
    }
}
