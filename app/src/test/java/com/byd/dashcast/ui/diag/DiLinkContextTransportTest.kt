package com.byd.dashcast.ui.diag

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DiLinkContextTransportTest {
    @Test fun `timed out physical call stays quarantined without accumulating threads or releasing diagnostic gate`() {
        val transport = DiLinkContextTransport(timeoutMs = 30)
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val idle = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            val first = transport.execute {
                calls.incrementAndGet(); entered.countDown(); unblock.await()
                DiLinkContextTransport.Reply(0, "late")
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(first.fatal)
            assertNull(first.exitCode)
            assertTrue(transport.isBusy())
            transport.whenIdle { idle.countDown() }
            repeat(20) {
                val refused = transport.execute { calls.incrementAndGet(); error("must not be queued") }
                assertTrue(refused.fatal)
                assertTrue(refused.error.contains("no command submitted"))
            }
            assertEquals(1, calls.get())
            assertFalse(idle.await(30, TimeUnit.MILLISECONDS))
        } finally { unblock.countDown() }
        assertTrue(idle.await(1, TimeUnit.SECONDS))
        assertFalse(transport.isBusy())
        val next = transport.execute { calls.incrementAndGet(); DiLinkContextTransport.Reply(7, "recovered") }
        assertEquals(7, next.exitCode)
        assertEquals(2, calls.get())
    }

    @Test fun `transport exception keeps exit unknown and releases gate exactly once`() {
        val transport = DiLinkContextTransport(timeoutMs = 1000)
        val result = transport.execute { throw IllegalStateException("dead") }
        assertNull(result.exitCode)
        assertTrue(result.fatal)
        assertFalse(transport.isBusy())
        var releases = 0
        transport.whenIdle { releases++ }
        assertEquals(1, releases)
    }

    @Test fun `thread allocation and startup Error never strand the transport gate`() {
        for (duringCreation in listOf(true, false)) {
            val failure = OutOfMemoryError("synthetic thread failure")
            val transport = DiLinkContextTransport(timeoutMs = 1000, newThread = { task ->
                if (duringCreation) throw failure
                object : Thread(task) { override fun start() { throw failure } }
            })
            try {
                transport.execute { error("Command must not execute") }
                fail("Synthetic Error must propagate")
            } catch (caught: OutOfMemoryError) { assertSame(failure, caught) }
            assertFalse(transport.isBusy())
            var releases = 0
            transport.whenIdle { releases++ }
            assertEquals(1, releases)
        }
    }
}
