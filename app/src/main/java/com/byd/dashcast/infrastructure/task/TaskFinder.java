package com.byd.dashcast.infrastructure.task;

/**
 * Strategy interface for locating a running task by package name.
 *
 * Implementations vary by privilege level and API availability:
 *   - {@link AmTaskFinder}         — ActivityManager.getRunningTasks (fast, limited to caller)
 *   - {@link ProxyTaskFinder}      — daemon dumpsys recents + activities (shell uid 2000)
 *   - {@link AdbLocalTaskFinder}   — AdbLocalClient shell (fallback when daemon is down)
 *   - {@link ChainedTaskFinder}    — tries each strategy in order until one succeeds
 *
 * <b>Threading:</b> all implementations are safe to call from a background thread.
 * Blocking I/O (CountDownLatch, ADB socket) is expected; never call from main thread.
 */
public interface TaskFinder {

    int NOT_FOUND = -1;

    /**
     * Returns the numeric task ID for the running task whose top activity belongs
     * to {@code packageName}, or {@link #NOT_FOUND} if no such task exists.
     *
     * @throws TaskFinderException if the underlying mechanism fails in a non-recoverable way
     */
    int findTaskId(String packageName) throws TaskFinderException;

    class TaskFinderException extends Exception {
        public TaskFinderException(String message) { super(message); }
        public TaskFinderException(String message, Throwable cause) { super(message, cause); }
    }
}
