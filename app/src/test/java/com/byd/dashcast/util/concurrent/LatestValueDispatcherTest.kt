package com.byd.dashcast.util.concurrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LatestValueDispatcherTest {

    @Test
    fun burstKeepsOnlyLatestPendingValue() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val latestHandled = CountDownLatch(1)
        val values = Collections.synchronizedList(mutableListOf<Int>())
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = LatestValueDispatcher(executor) { value: Int ->
            values += value
            if (value == 1) {
                firstEntered.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
            }
            if (value == 3) latestHandled.countDown()
        }

        assertTrue(dispatcher.submit(1))
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
        assertTrue(dispatcher.submit(2))
        assertTrue(dispatcher.submit(3))
        releaseFirst.countDown()
        assertTrue(latestHandled.await(1, TimeUnit.SECONDS))
        dispatcher.close { }
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))

        assertEquals(listOf(1, 3), values)
    }

    @Test
    fun closeRunsAfterActiveUpdateAndRejectsLateValues() {
        val updateEntered = CountDownLatch(1)
        val releaseUpdate = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = LatestValueDispatcher(executor) { _: Int ->
            events += "update"
            updateEntered.countDown()
            releaseUpdate.await(2, TimeUnit.SECONDS)
        }

        dispatcher.submit(1)
        assertTrue(updateEntered.await(1, TimeUnit.SECONDS))
        dispatcher.submit(2)
        dispatcher.close { events += "close" }
        assertFalse(dispatcher.submit(3))
        releaseUpdate.countDown()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))

        assertEquals(listOf("update", "close"), events)
    }

    @Test
    fun cancelPendingActionRunsAfterActiveUpdate() {
        val updateEntered = CountDownLatch(1)
        val releaseUpdate = CountDownLatch(1)
        val closeRan = CountDownLatch(1)
        val values = Collections.synchronizedList(mutableListOf<Int>())
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = LatestValueDispatcher(executor) { value: Int ->
            values += value
            if (value == 1) {
                updateEntered.countDown()
                releaseUpdate.await(2, TimeUnit.SECONDS)
            }
        }

        dispatcher.submit(1)
        assertTrue(updateEntered.await(1, TimeUnit.SECONDS))
        dispatcher.submit(2)
        assertTrue(dispatcher.cancelPendingAndExecute { closeRan.countDown() })
        releaseUpdate.countDown()
        assertTrue(closeRan.await(1, TimeUnit.SECONDS))
        dispatcher.close { }
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))

        assertEquals(listOf(1), values)
    }
}
