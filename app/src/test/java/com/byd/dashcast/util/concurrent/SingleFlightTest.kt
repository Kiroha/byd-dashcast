package com.byd.dashcast.util.concurrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightTest {

    @Test
    fun concurrentCallersShareOneLeaderAndResult() {
        val singleFlight = SingleFlight<Boolean>()
        val callers = 8
        val ready = CountDownLatch(callers)
        val start = CountDownLatch(1)
        val joined = CountDownLatch(callers)
        val leaderEntered = CountDownLatch(1)
        val releaseLeader = CountDownLatch(1)
        val leaders = AtomicInteger()
        val results = Collections.synchronizedList(mutableListOf<Boolean>())
        val pool = Executors.newFixedThreadPool(callers)

        repeat(callers) {
            pool.execute {
                ready.countDown()
                start.await()
                val ticket = singleFlight.join()
                joined.countDown()
                if (ticket.isLeader) {
                    leaders.incrementAndGet()
                    leaderEntered.countDown()
                    releaseLeader.await()
                    ticket.complete(true)
                }
                results += ticket.await(2, TimeUnit.SECONDS)
            }
        }

        assertTrue(ready.await(1, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(leaderEntered.await(1, TimeUnit.SECONDS))
        assertTrue(joined.await(1, TimeUnit.SECONDS))
        releaseLeader.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))

        assertEquals(1, leaders.get())
        assertEquals(List(callers) { true }, results.sortedBy { it })
        assertTrue(singleFlight.join().isLeader)
    }

    @Test
    fun failureResultIsSharedAndCompletionAllowsANewLeader() {
        val singleFlight = SingleFlight<Boolean>()
        val leader = singleFlight.join()
        val follower = singleFlight.join()

        assertTrue(leader.isLeader)
        leader.complete(false)

        assertEquals(false, follower.await(1, TimeUnit.SECONDS))
        assertTrue(singleFlight.join().isLeader)
    }

    @Test
    fun followerTimeoutDoesNotCreateASecondLeader() {
        val singleFlight = SingleFlight<Boolean>()
        val leader = singleFlight.join()
        val follower = singleFlight.join()

        try {
            follower.await(1, TimeUnit.MILLISECONDS)
            throw AssertionError("expected timeout")
        } catch (_: TimeoutException) {
        }

        val stillFollower = singleFlight.join()
        assertEquals(false, stillFollower.isLeader)
        leader.complete(true)
        assertEquals(true, stillFollower.await(1, TimeUnit.SECONDS))
    }
}
