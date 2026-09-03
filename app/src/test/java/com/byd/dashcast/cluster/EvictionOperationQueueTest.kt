package com.byd.dashcast.cluster

import org.junit.Assert.assertEquals
import org.junit.Test

class EvictionOperationQueueTest {
    @Test
    fun `next eviction waits for physical and caller completion`() {
        val queue = EvictionOperationQueue()
        val started = mutableListOf<Int>()
        lateinit var first: EvictionOperationQueue.Lease
        lateinit var second: EvictionOperationQueue.Lease

        queue.submit({ lease -> first = lease; started += 1 }, Runnable {})
        queue.submit({ lease -> second = lease; started += 2 }, Runnable {})
        assertEquals(listOf(1), started)

        first.markPhysicalDone()
        assertEquals(listOf(1), started)
        first.markCallerDone()
        assertEquals(listOf(1, 2), started)

        second.markCallerDone()
        second.markPhysicalDone()
        assertEquals(listOf(1, 2), started)
    }
}