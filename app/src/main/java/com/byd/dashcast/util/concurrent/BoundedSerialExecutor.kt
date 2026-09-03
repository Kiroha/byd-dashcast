package com.byd.dashcast.util.concurrent

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Single-worker FIFO executor with explicit bounded backpressure and no caller-thread fallback. */
class BoundedSerialExecutor(queueCapacity: Int, threadFactory: ThreadFactory?) : Executor {

    private val delegate: ThreadPoolExecutor

    init {
        require(queueCapacity > 0) { "queueCapacity must be positive" }
        requireNotNull(threadFactory) { "threadFactory required" }
        delegate = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(queueCapacity),
            threadFactory,
            ThreadPoolExecutor.AbortPolicy())
    }

    override fun execute(command: Runnable) {
        delegate.execute(command)
    }

    fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()
}
