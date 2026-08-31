package com.byd.dashcast.util.concurrent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RecoverableSerialExecutorTest {

    @Test
    fun `retiring blocked worker preserves queued work and restores capacity`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queuedRan = CountDownLatch(1)
        val newWorkRan = CountDownLatch(1)
        val executor = RecoverableSerialExecutor { runnable ->
            Thread(runnable, "recoverable-test").apply { isDaemon = true }
        }
        val blocked = executor.submit(Callable {
            entered.countDown()
            while (release.count > 0) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // Model a Binder transact that does not honor cancellation.
                }
            }
            1
        })

        assertTrue(entered.await(1, TimeUnit.SECONDS))
        executor.execute { queuedRan.countDown() }
        val queuedFuture = executor.submit(Callable { 42 })
        assertTrue(executor.retire(blocked))
        executor.execute { newWorkRan.countDown() }

        assertTrue(queuedRan.await(1, TimeUnit.SECONDS))
        assertEquals(42, queuedFuture.future.get(1, TimeUnit.SECONDS))
        assertTrue(newWorkRan.await(1, TimeUnit.SECONDS))
        assertFalse(executor.retire(blocked))
        release.countDown()
        executor.shutdownNow()
    }
}