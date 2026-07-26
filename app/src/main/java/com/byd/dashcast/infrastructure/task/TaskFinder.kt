package com.byd.dashcast.infrastructure.task

/**
 * Strategy interface for locating a running task by package name.
 *
 * Implementations vary by privilege level and API availability:
 *   - [AmTaskFinder]         — ActivityManager.getRunningTasks (fast, limited to caller)
 *   - [TypedProxyTaskFinder] — daemon IActivityTaskManager.getTasks (fast, privileged)
 *   - [ProxyTaskFinder]      — daemon dumpsys recents + activities (shell uid 2000)
 *   - [AdbLocalTaskFinder]   — AdbLocalClient shell (fallback when daemon is down)
 *   - [ChainedTaskFinder]    — tries each strategy in order until one succeeds
 *
 * **Threading:** all implementations are safe to call from a background thread.
 * Blocking I/O (CountDownLatch, ADB socket) is expected; never call from main thread.
 */
interface TaskFinder {

    /**
     * Returns the numeric task ID for the running task whose top activity belongs
     * to [packageName], or [NOT_FOUND] if no such task exists.
     *
     * @throws TaskFinderException if the underlying mechanism fails in a non-recoverable way
     */
    @Throws(TaskFinderException::class)
    fun findTaskId(packageName: String): Int

    class TaskFinderException : Exception {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }

    companion object {
        const val NOT_FOUND = -1
    }
}
