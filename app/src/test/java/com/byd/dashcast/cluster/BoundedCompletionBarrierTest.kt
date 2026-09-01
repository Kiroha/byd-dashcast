package com.byd.dashcast.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedCompletionBarrierTest {

    @Test
    fun `last callback closes once after applying accepted mutations`() {
        val events = mutableListOf<String>()
        val barrier = BoundedCompletionBarrier(2, Runnable { events += "closed" })

        assertTrue(barrier.complete(Runnable { events += "one" }))
        assertTrue(barrier.complete(Runnable { events += "two" }))
        assertFalse(barrier.complete(Runnable { events += "late" }))

        assertEquals(listOf("one", "two", "closed"), events)
    }

    @Test
    fun `timeout closes once and rejects late mutation`() {
        val events = mutableListOf<String>()
        val barrier = BoundedCompletionBarrier(2, Runnable { events += "closed" })

        barrier.complete(Runnable { events += "one" })
        assertTrue(barrier.timeout())
        assertFalse(barrier.complete(Runnable { events += "late-remove" }))
        assertFalse(barrier.timeout())

        assertEquals(listOf("one", "closed"), events)
    }
}