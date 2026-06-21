package com.byd.dashcast.infrastructure.task

import com.byd.dashcast.util.AppLogger

/**
 * Tries each [TaskFinder] in order and returns the first non-[TaskFinder.NOT_FOUND] result.
 * Logs and swallows [TaskFinder.TaskFinderException] from each step so the chain continues.
 */
class ChainedTaskFinder(vararg finders: TaskFinder) : TaskFinder {

    private val mChain: List<TaskFinder> = finders.asList()

    @Throws(TaskFinder.TaskFinderException::class)
    override fun findTaskId(packageName: String): Int {
        for (finder in mChain) {
            try {
                val id = finder.findTaskId(packageName)
                if (id != TaskFinder.NOT_FOUND) {
                    AppLogger.d(TAG, "findTaskId $packageName → $id via ${finder.javaClass.simpleName}")
                    return id
                }
            } catch (e: TaskFinder.TaskFinderException) {
                AppLogger.w(TAG, "${finder.javaClass.simpleName} failed for $packageName: ${e.message}")
            }
        }
        AppLogger.w(TAG, "findTaskId: all finders exhausted for $packageName")
        return TaskFinder.NOT_FOUND
    }

    companion object {
        private const val TAG = "ChainedTaskFinder"
    }
}
