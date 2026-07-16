package com.byd.dashcast.util.concurrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CloseableResourceSlotTest {

    @Test
    fun resourcePublishedAfterReleaseIsClosedImmediately() {
        val slot = CloseableResourceSlot<FakeResource> { it.close() }
        val resource = FakeResource()

        slot.release()

        assertFalse(slot.publish(resource))
        assertEquals(1, resource.closeCount)
    }

    @Test
    fun releaseWaitsForActiveUser() {
        val slot = CloseableResourceSlot<FakeResource> { it.close() }
        val resource = FakeResource()
        assertTrue(slot.publish(resource))
        val acquired = slot.acquire()
        assertSame(resource, acquired)

        slot.release()
        assertEquals(0, resource.closeCount)

        slot.releaseUse(acquired!!)
        assertEquals(1, resource.closeCount)
    }

    @Test
    fun idleReleaseClosesExactlyOnce() {
        val slot = CloseableResourceSlot<FakeResource> { it.close() }
        val resource = FakeResource()
        assertTrue(slot.publish(resource))

        slot.release()
        slot.release()

        assertEquals(1, resource.closeCount)
        assertFalse(slot.hasResource())
    }

    @Test
    fun concurrentPublishAndReleaseStillCloseExactlyOnce() {
        repeat(100) {
            val slot = CloseableResourceSlot<FakeResource> { it.close() }
            val resource = FakeResource()
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            pool.execute { start.await(); slot.publish(resource) }
            pool.execute { start.await(); slot.release() }

            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS))
            slot.release()

            assertEquals(1, resource.closeCount)
            assertFalse(slot.hasResource())
        }
    }

    private class FakeResource {
        var closeCount = 0
        fun close() { closeCount++ }
    }
}
