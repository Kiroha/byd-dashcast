package com.byd.dashcast.infrastructure.task

import android.graphics.Rect

/**
 * Strategy interface for resizing a cluster task to given bounds.
 *
 * Implementations:
 *   - [ReflectionTaskResizer]  — IActivityTaskManager.resizeTask (3-arg then 2-arg)
 *   - [ShellTaskResizer]       — `am task resize` / `cmd activity task resize`
 *   - [ChainedTaskResizer]     — tries reflection; on [ResizeException] falls to shell
 */
interface TaskResizer {

    /**
     * Resizes task [taskId] to [bounds].
     *
     * @param taskId      numeric AMS task ID (must be > 0)
     * @param packageName package owning the task (used only for logging)
     * @param bounds      target bounds in the task's own display coordinate space
     * @throws ResizeException if this strategy cannot perform the resize
     */
    @Throws(ResizeException::class)
    fun resize(taskId: Int, packageName: String, bounds: Rect)

    class ResizeException : Exception {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable?) : super(message, cause)
    }
}
