package com.byd.dashcast.util.concurrent

import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory

/** Serial executor whose worker can be retired after an uninterruptible blocking call. */
class RecoverableSerialExecutor(private val threadFactory: ThreadFactory) : Executor {

    class Submission<T> internal constructor(
        internal val owner: ExecutorService,
        val future: Future<T>,
    )

    private val lock = Any()
    @Volatile private var delegate: ExecutorService = newExecutor()

    override fun execute(command: Runnable) {
        synchronized(lock) {
            delegate.execute(command)
        }
    }

    fun <T> submit(task: Callable<T>): Submission<T> = synchronized(lock) {
        val owner = delegate
        Submission(owner, owner.submit(task))
    }

    /** Cancels [submission], replaces its worker if still current, and preserves queued work. */
    fun retire(submission: Submission<*>): Boolean {
        submission.future.cancel(true)
        synchronized(lock) {
            if (delegate !== submission.owner) return false
            val retired = delegate
            val queued = retired.shutdownNow()
            val replacement = newExecutor()
            delegate = replacement
            for (task in queued) {
                if (task !== submission.future) replacement.execute(task)
            }
            return true
        }
    }

    fun shutdownNow() {
        synchronized(lock) { delegate.shutdownNow() }
    }

    private fun newExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor(threadFactory)
}