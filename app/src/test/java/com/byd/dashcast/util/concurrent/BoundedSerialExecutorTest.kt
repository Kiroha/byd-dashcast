package com.byd.dashcast.util.concurrent

import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class BoundedSerialExecutorTest {

    @Test(expected = RejectedExecutionException::class)
    fun rejectsInsteadOfRunningOnCallerWhenQueueIsFull() {
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val callerRan = booleanArrayOf(false)
        val executor = BoundedSerialExecutor(1) { runnable ->
            Thread(runnable, "bounded-test").apply { isDaemon = true }
        }
        try {
            executor.execute {
                workerEntered.countDown()
                try {
                    releaseWorker.await(2, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            workerEntered.await(1, TimeUnit.SECONDS)
            executor.execute { }
            executor.execute { callerRan[0] = true }
        } finally {
            releaseWorker.countDown()
            executor.shutdownNow()
            assertFalse(callerRan[0])
        }
    }
}
