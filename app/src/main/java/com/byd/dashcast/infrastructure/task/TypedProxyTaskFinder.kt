package com.byd.dashcast.infrastructure.task

import com.byd.dashcast.proxy.ProxyClient

/** Fast daemon-side IActivityTaskManager task lookup, without a shell/dumpsys fork. */
class TypedProxyTaskFinder : TaskFinder {
    override fun findTaskId(packageName: String): Int {
        if (!ProxyClient.isConnected()) {
            throw TaskFinder.TaskFinderException("daemon not connected")
        }
        return try {
            normalizeTaskId(ProxyClient.findTaskIdForPackage(packageName))
        } catch (t: Throwable) {
            throw TaskFinder.TaskFinderException("typed daemon lookup failed", t)
        }
    }

    companion object {
        internal fun normalizeTaskId(taskId: Int): Int =
            if (taskId > 0) taskId else TaskFinder.NOT_FOUND
    }
}