package com.byd.dashcast.infrastructure.task

import android.app.ActivityManager
import android.content.Context

import com.byd.dashcast.util.AppLogger

/**
 * Path 1: [ActivityManager.getRunningTasks].
 *
 * On API 21+ this only returns the caller's own task for non-system apps,
 * so this succeeds only when DashCast itself holds GET_TASKS (legacy BYD ROMs).
 * Fast O(n) scan; kept as the head of the chain for those cases.
 */
class AmTaskFinder(context: Context) : TaskFinder {

    private val mContext: Context = context.applicationContext

    @Throws(TaskFinder.TaskFinderException::class)
    @Suppress("DEPRECATION") // getRunningTasks + RunningTaskInfo.id: only API available on these ROMs
    override fun findTaskId(packageName: String): Int {
        try {
            val am = mContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return TaskFinder.NOT_FOUND
            val tasks = am.getRunningTasks(50) ?: return TaskFinder.NOT_FOUND
            for (t in tasks) {
                val top = t.topActivity
                if (top != null && packageName == top.packageName) {
                    AppLogger.d(TAG, "$packageName → taskId=${t.id} (AM)")
                    return t.id
                }
            }
        } catch (e: Exception) {
            throw TaskFinder.TaskFinderException("AM getRunningTasks failed: " + e.message, e)
        }
        return TaskFinder.NOT_FOUND
    }

    companion object {
        private const val TAG = "AmTaskFinder"
    }
}
