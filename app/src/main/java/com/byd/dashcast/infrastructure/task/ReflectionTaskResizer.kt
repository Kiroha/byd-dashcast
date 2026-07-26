package com.byd.dashcast.infrastructure.task

import android.graphics.Rect

import com.byd.dashcast.util.AppLogger

import java.lang.reflect.InvocationTargetException

/**
 * Resizes a task via `IActivityTaskManager.resizeTask()`.
 *
 * Tries the 3-arg form (Android 11/12: taskId, Rect, resizeMode) first,
 * then the 2-arg form (older vendor ROMs). Both arms surface the real cause
 * from [InvocationTargetException] so the caller's error logs are useful.
 *
 * Throws [TaskResizer.ResizeException] if both signatures are absent or fail, so
 * [ChainedTaskResizer] can cascade to the shell-based fallback.
 */
class ReflectionTaskResizer : TaskResizer {

    @Throws(TaskResizer.ResizeException::class)
    override fun resize(taskId: Int, packageName: String, bounds: Rect) {
        try {
            val iAtmClass = Class.forName("android.app.IActivityTaskManager")
            val iatm: Any? = try {
                Class.forName("android.app.ActivityTaskManager")
                    .getMethod("getService").invoke(null)
            } catch (e: Exception) {
                Class.forName("android.app.ActivityManager")
                    .getMethod("getService").invoke(null)
            }

            // int.class — the primitive Class is what getMethod() must match against.
            val intType = Int::class.javaPrimitiveType
            var lastError: Throwable? = null
            var done = false

            // 3-arg form (preferred — AOSP API 30+)
            try {
                iAtmClass.getMethod("resizeTask", intType, Rect::class.java, intType)
                    .invoke(iatm, taskId, bounds, RESIZE_MODE_FORCED)
                done = true
            } catch (ite: InvocationTargetException) {
                lastError = ite.targetException ?: ite
            } catch (nsme: NoSuchMethodException) {
                lastError = nsme
            } catch (t: Throwable) {
                lastError = t
            }

            // 2-arg form (older / vendor variants)
            if (!done) {
                try {
                    iAtmClass.getMethod("resizeTask", intType, Rect::class.java)
                        .invoke(iatm, taskId, bounds)
                    done = true
                    lastError = null
                } catch (ite: InvocationTargetException) {
                    if (lastError == null) lastError = ite.targetException ?: ite
                } catch (ignored: NoSuchMethodException) {
                    // keep first error
                } catch (t: Throwable) {
                    if (lastError == null) lastError = t
                }
            }

            if (done) {
                AppLogger.i(TAG, "resizeTask $packageName $bounds OK")
                return
            }

            val detail = if (lastError == null) "unknown"
                else lastError.javaClass.simpleName + ": " + lastError.message
            throw TaskResizer.ResizeException(
                "reflection failed for taskId=$taskId pkg=$packageName: $detail",
                if (lastError is Exception) lastError else null
            )
        } catch (re: TaskResizer.ResizeException) {
            throw re
        } catch (e: Exception) {
            throw TaskResizer.ResizeException("outer reflection error: " + e.message, e)
        }
    }

    companion object {
        private const val TAG = "ReflectionTaskResizer"
        private const val RESIZE_MODE_FORCED = 1
    }
}
