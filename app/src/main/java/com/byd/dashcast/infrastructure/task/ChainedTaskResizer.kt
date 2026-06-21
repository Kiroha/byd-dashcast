package com.byd.dashcast.infrastructure.task

import android.content.Context
import android.graphics.Rect

import com.byd.dashcast.util.AppLogger

/**
 * Tries [ReflectionTaskResizer] first; if it throws [TaskResizer.ResizeException]
 * (method stripped on this ROM, SecurityException, etc.) falls through to
 * [ShellTaskResizer].
 *
 * This two-step cascade is the verbatim logic of the original
 * `ClusterService.resizeActiveTask()` now expressed as composed strategies,
 * making each resizer independently testable.
 */
class ChainedTaskResizer(context: Context) : TaskResizer {

    private val mReflection = ReflectionTaskResizer()
    private val mShell = ShellTaskResizer(context)

    @Throws(TaskResizer.ResizeException::class)
    override fun resize(taskId: Int, packageName: String, bounds: Rect) {
        try {
            mReflection.resize(taskId, packageName, bounds)
        } catch (e: TaskResizer.ResizeException) {
            AppLogger.w(TAG, "Reflection resize failed for $packageName — falling to shell: ${e.message}")
            mShell.resize(taskId, packageName, bounds)
        }
    }

    companion object {
        private const val TAG = "ChainedTaskResizer"
    }
}
