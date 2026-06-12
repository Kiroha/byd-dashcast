package com.byd.dashcast.infrastructure.task;

import com.byd.dashcast.util.AppLogger;

import java.util.Arrays;
import java.util.List;

/**
 * Tries each {@link TaskFinder} in order and returns the first non-{@link #NOT_FOUND} result.
 * Logs and swallows {@link TaskFinderException} from each step so the chain continues.
 */
public final class ChainedTaskFinder implements TaskFinder {

    private static final String TAG = "ChainedTaskFinder";

    private final List<TaskFinder> mChain;

    public ChainedTaskFinder(TaskFinder... finders) {
        mChain = Arrays.asList(finders);
    }

    @Override
    public int findTaskId(String packageName) throws TaskFinderException {
        for (TaskFinder finder : mChain) {
            try {
                int id = finder.findTaskId(packageName);
                if (id != NOT_FOUND) {
                    AppLogger.d(TAG, "findTaskId " + packageName
                            + " → " + id + " via " + finder.getClass().getSimpleName());
                    return id;
                }
            } catch (TaskFinderException e) {
                AppLogger.w(TAG, finder.getClass().getSimpleName()
                        + " failed for " + packageName + ": " + e.getMessage());
            }
        }
        AppLogger.w(TAG, "findTaskId: all finders exhausted for " + packageName);
        return NOT_FOUND;
    }
}
