package com.byd.dashcast.infrastructure.task

import android.content.Context

import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.util.AppLogger

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Paths 3 + 3b: AdbLocalClient shell fallback.
 *
 * Used when the proxy daemon is not running. Blocks with a [CountDownLatch]
 * (5 s per attempt). Both `dumpsys activity recents` and
 * `dumpsys activity activities` are tried before giving up.
 *
 * **Threading:** must be called from a background thread — blocks up to 10 s total.
 */
class AdbLocalTaskFinder(context: Context) : TaskFinder {

    private val mContext: Context = context.applicationContext

    @Throws(TaskFinder.TaskFinderException::class)
    override fun findTaskId(packageName: String): Int {
        // Path 3 — AdbLocal dumpsys activity recents
        val recents = runShellBlocking("dumpsys activity recents", "$packageName recents")
        if (!recents.isNullOrEmpty()) {
            val id = ProxyTaskFinder.parseFromRecents(recents, packageName)
            if (id != TaskFinder.NOT_FOUND) {
                AppLogger.d(TAG, "$packageName → $id (AdbLocal recents)")
                return id
            }
        }
        // Path 3b — AdbLocal dumpsys activity activities
        val activities = runShellBlocking("dumpsys activity activities", "$packageName activities")
        if (!activities.isNullOrEmpty()) {
            val id = ProxyTaskFinder.parseFromActivities(activities, packageName)
            if (id != TaskFinder.NOT_FOUND) {
                AppLogger.d(TAG, "$packageName → $id (AdbLocal activities)")
                return id
            }
        }
        return TaskFinder.NOT_FOUND
    }

    @Throws(TaskFinder.TaskFinderException::class)
    private fun runShellBlocking(cmd: String, label: String): String? {
        val outRef = AtomicReference<String?>()
        val errRef = AtomicReference<String?>()
        val latch = CountDownLatch(1)

        AdbLocalClient.executeShellWithResult(mContext, cmd, object : AdbLocalClient.Callback {
            override fun onSuccess(out: String?) { outRef.set(out); latch.countDown() }
            override fun onError(err: String?) { errRef.set(err); latch.countDown() }
        })

        try {
            if (!latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                throw TaskFinder.TaskFinderException("AdbLocal timeout (${TIMEOUT_SEC}s) for $label")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TaskFinder.TaskFinderException("AdbLocal interrupted for $label", e)
        }

        val err = errRef.get()
        if (err != null) {
            AppLogger.w(TAG, "AdbLocal error for $label: $err")
            return null
        }
        return outRef.get()
    }

    companion object {
        private const val TAG = "AdbLocalTaskFinder"
        private const val TIMEOUT_SEC = 5L
    }
}
