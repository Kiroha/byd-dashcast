package com.byd.dashcast.infrastructure.task;

import android.graphics.Rect;

/**
 * Strategy interface for resizing a cluster task to given bounds.
 *
 * Implementations:
 *   - {@link ReflectionTaskResizer}  — IActivityTaskManager.resizeTask (3-arg then 2-arg)
 *   - {@link ShellTaskResizer}       — {@code am task resize} / {@code cmd activity task resize}
 *   - {@link ChainedTaskResizer}     — tries reflection; on {@link ResizeException} falls to shell
 */
public interface TaskResizer {

    /**
     * Resizes task {@code taskId} to {@code bounds}.
     *
     * @param taskId      numeric AMS task ID (must be > 0)
     * @param packageName package owning the task (used only for logging)
     * @param bounds      target bounds in the task's own display coordinate space
     * @throws ResizeException if this strategy cannot perform the resize
     */
    void resize(int taskId, String packageName, Rect bounds) throws ResizeException;

    class ResizeException extends Exception {
        public ResizeException(String message) { super(message); }
        public ResizeException(String message, Throwable cause) { super(message, cause); }
    }
}
