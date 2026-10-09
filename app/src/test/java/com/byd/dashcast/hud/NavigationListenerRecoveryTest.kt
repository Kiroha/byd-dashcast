package com.byd.dashcast.hud

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NavigationListenerRecoveryTest {
    @Test
    fun `cold start requests immediately and zero uptime still respects retry cadence`() {
        val recovery = NavigationListenerRecovery()
        var requests = 0
        fun tick(now: Long) = recovery.maybeRecover(now, true, true) { requests++ }

        assertTrue(tick(0))
        assertFalse(tick(0))
        assertFalse(tick(10_000))
        assertFalse(tick(29_999))
        assertTrue(tick(30_000))
        assertEquals(2, requests)
    }

    @Test
    fun `an unconfirmed request backs off without ever giving up`() {
        val recovery = NavigationListenerRecovery()
        var requests = 0
        for (time in listOf(0L, 30_000L, 90_000L, 210_000L, 510_000L, 810_000L)) {
            assertTrue(recovery.maybeRecover(time, true, true) { requests++ })
            assertFalse(recovery.maybeRecover(time + 10_000L, true, true) { requests++ })
            assertFalse("request acceptance is not connectivity", recovery.snapshot(time).connected)
        }
        assertTrue(recovery.maybeRecover(3_600_000L, true, true) { requests++ })
        assertEquals(7, requests)
    }

    @Test
    fun `a failed Binder request consumes the retry interval`() {
        val recovery = NavigationListenerRecovery()
        assertThrows(SecurityException::class.java) {
            recovery.maybeRecover(0, true, true) { throw SecurityException() }
        }
        assertFalse(recovery.snapshot(0).inFlight)
        assertFalse(recovery.maybeRecover(10_000, true, true) { fail("too early") })
        assertTrue(recovery.maybeRecover(30_000, true, true) {})
    }

    @Test
    fun `a connected listener can stay idle for hours without a rebind`() {
        val recovery = NavigationListenerRecovery()
        val token = Any()
        recovery.onCreated(token)
        recovery.onConnected(token)

        for (time in listOf(0L, 30_000L, 3_600_000L)) {
            assertFalse(recovery.maybeRecover(time, true, true) { fail("healthy listener") })
        }
        assertTrue(recovery.snapshot(3_600_000L).connected)
    }

    @Test
    fun `service creation alone does not prove notification binding`() {
        val recovery = NavigationListenerRecovery()
        recovery.onCreated(Any())
        assertTrue(recovery.maybeRecover(0, true, true) {})
        assertFalse(recovery.snapshot(0).connected)
    }

    @Test
    fun `a confirmed reconnect resets backoff for the next real disconnect`() {
        val recovery = NavigationListenerRecovery()
        val token = Any()
        recovery.onCreated(token)
        recovery.maybeRecover(0, true, true) {}
        recovery.maybeRecover(30_000, true, true) {}
        recovery.onConnected(token)
        recovery.onDisconnected(token)

        assertTrue(recovery.maybeRecover(31_000, true, true) {})
        recovery.onDisconnected(token)
        assertFalse("duplicate disconnect must not reset retries",
            recovery.maybeRecover(32_000, true, true) { fail("duplicate callback") })
    }

    @Test
    fun `destruction without disconnect allows recovery on the next probe`() {
        val recovery = NavigationListenerRecovery()
        val token = Any()
        recovery.onCreated(token)
        recovery.onConnected(token)
        recovery.onDestroyed(token)
        assertTrue(recovery.maybeRecover(0, true, true) {})
    }

    @Test
    fun `late callbacks from a replaced service cannot invalidate the new connection`() {
        val recovery = NavigationListenerRecovery()
        val old = Any()
        val current = Any()
        recovery.onCreated(old)
        recovery.onConnected(old)
        recovery.onCreated(current)
        recovery.onConnected(current)

        assertFalse(recovery.onDisconnected(old))
        recovery.onDestroyed(old)
        assertFalse(recovery.onConnected(old))
        assertTrue(recovery.snapshot(0).connected)
        assertFalse(recovery.maybeRecover(0, true, true) { fail("new listener is alive") })
    }

    @Test
    fun `disabled guidance and missing permission suppress requests and reset old backoff`() {
        val recovery = NavigationListenerRecovery()
        recovery.maybeRecover(0, true, true) {}
        assertFalse(recovery.maybeRecover(1_000, false, true) { fail("disabled guidance") })
        assertTrue(recovery.maybeRecover(2_000, true, true) {})
        assertFalse(recovery.maybeRecover(3_000, true, false) { fail("no permission") })
        assertTrue(recovery.maybeRecover(4_000, true, true) {})
    }

    @Test
    fun `permission loss invalidates connectivity even without a disconnect callback`() {
        val recovery = NavigationListenerRecovery()
        val token = Any()
        recovery.onCreated(token)
        recovery.onConnected(token)
        assertFalse(recovery.maybeRecover(0, true, false) { fail("no permission") })
        assertFalse(recovery.snapshot(0).connected)
        assertTrue(recovery.maybeRecover(1_000, true, true) {})
    }

    @Test
    fun `turning guidance off preserves a healthy listener binding`() {
        val recovery = NavigationListenerRecovery()
        val token = Any()
        recovery.onCreated(token)
        recovery.onConnected(token)
        assertFalse(recovery.maybeRecover(0, false, false) { fail("disabled") })
        assertTrue(recovery.snapshot(0).connected)
        assertFalse(recovery.maybeRecover(1_000, true, true) { fail("still connected") })
    }

    @Test
    fun `an in flight request remains single flight while lifecycle callbacks stay responsive`() {
        val recovery = NavigationListenerRecovery()
        val token = Any()
        recovery.onCreated(token)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val request = workers.submit<Boolean> {
                recovery.maybeRecover(0, true, true) {
                    entered.countDown()
                    assertTrue(release.await(3, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertFalse(recovery.maybeRecover(3_600_000L, true, true) { fail("parallel request") })
            val connection = workers.submit<Boolean> { recovery.onConnected(token) }
            assertTrue("Binder request must not hold the lifecycle lock",
                connection.get(1, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(request.get(3, TimeUnit.SECONDS))
            assertTrue(recovery.snapshot(3_600_000L).connected)
            assertFalse(recovery.maybeRecover(3_600_001L, true, true) { fail("connected") })
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
}
