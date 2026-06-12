package com.byd.dashcast.infrastructure.task;

import android.content.Context;
import android.graphics.Rect;

import com.byd.dashcast.util.AppLogger;

/**
 * Tries {@link ReflectionTaskResizer} first; if it throws {@link ResizeException}
 * (method stripped on this ROM, SecurityException, etc.) falls through to
 * {@link ShellTaskResizer}.
 *
 * This two-step cascade is the verbatim logic of the original
 * {@code ClusterService.resizeActiveTask()} now expressed as composed strategies,
 * making each resizer independently testable.
 */
public final class ChainedTaskResizer implements TaskResizer {

    private static final String TAG = "ChainedTaskResizer";

    private final ReflectionTaskResizer mReflection;
    private final ShellTaskResizer      mShell;

    public ChainedTaskResizer(Context context) {
        mReflection = new ReflectionTaskResizer();
        mShell      = new ShellTaskResizer(context);
    }

    @Override
    public void resize(int taskId, String packageName, Rect bounds) throws ResizeException {
        try {
            mReflection.resize(taskId, packageName, bounds);
        } catch (ResizeException e) {
            AppLogger.w(TAG, "Reflection resize failed for " + packageName
                    + " — falling to shell: " + e.getMessage());
            mShell.resize(taskId, packageName, bounds);
        }
    }
}
