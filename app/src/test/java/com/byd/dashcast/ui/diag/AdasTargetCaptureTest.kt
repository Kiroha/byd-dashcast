package com.byd.dashcast.ui.diag

import com.byd.dashcast.proxy.AdasTargetProperties
import com.byd.dashcast.proxy.AdasTargetProperties.Variant
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AdasTargetCaptureTest {
    private fun waitFor(check: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!check() && System.nanoTime() < end) Thread.sleep(2)
        assertTrue("Condition did not complete", check())
    }
    private fun snapshot(variant: Variant?, values: List<Int>): AdasTargetSnapshot {
        val rows = AdasTargetProperties.allowed.map { AdasTargetRow(it, "RAW", 0) }.toMutableList()
        if (variant != null) AdasTargetProperties.keys(variant, AdasTargetProperties.groups.first()).forEachIndexed { index, key ->
            rows[rows.indexOfFirst { it.property == key }] = AdasTargetRow(key, "RAW", values[index])
        }
        return AdasTargetSnapshot("fixture", variant, "runtime unverified", 1, outcome = "COMPLETE", rows = rows)
    }

    @Test fun `decoder uses one explicit hypothesis and never treats zeros errors or incomplete sets as targets`() {
        assertTrue(decodeAdasTargets(snapshot(null, emptyList())).isEmpty())
        assertTrue(decodeAdasTargets(snapshot(Variant.NEW, listOf(0, 0, 0, 0))).isEmpty())
        val valid = snapshot(Variant.NEW, listOf(1, 2, 120, 250))
        val point = decodeAdasTargets(valid).single()
        assertEquals(0.0, point.x, 0.00001); assertEquals(15.0, point.y, 0.00001)
        assertEquals(2, point.type)
        for (bad in listOf(listOf(1, 2, -10011, 250), listOf(1, 2, 241, 250), listOf(1, 2, 120, 1601),
            listOf(1, 5, 120, 250), listOf(2, 2, 120, 250))) {
            assertTrue(decodeAdasTargets(snapshot(Variant.NEW, bad)).isEmpty())
        }
        assertTrue(decodeAdasTargets(valid.copy(outcome = "CANCELLED")).isEmpty())
        assertTrue(decodeAdasTargets(valid.copy(rows = valid.rows.drop(1))).isEmpty())
        assertTrue(decodeAdasTargets(valid.copy(rows = valid.rows.mapIndexed { index, row -> if (index == 0) row.copy(status = "ERROR") else row })).isEmpty())
        val old = decodeAdasTargets(snapshot(Variant.OLD, listOf(1, 4, 50, 175))).single()
        assertEquals(-5.0, old.x, 0.00001); assertEquals(75.0, old.y, 0.00001)
        assertTrue(decodeAdasTargets(snapshot(Variant.OLD, listOf(1, 4, 201, 100))).isEmpty())
        assertTrue(decodeAdasTargets(snapshot(Variant.OLD, listOf(1, 4, 100, -10013))).isEmpty())
    }

    @Test fun `manual capture retains all keys raw sentinel and root error without inventing valid values`() {
        val capture = AdasTargetCapture()
        assertNull(capture.snapshot())
        assertTrue(capture.summary().contains("No OEM reads"))
        val seen = mutableListOf<Int>()
        assertTrue(capture.start(null, "appUid=fixture") { property ->
            seen += property
            when (seen.size) {
                1 -> -10011
                2 -> -10013
                3 -> throw InvocationTargetException(SecurityException("OEM denied"))
                else -> 0
            }
        })
        waitFor { !capture.isBusy() }
        assertEquals(AdasTargetProperties.allowed, seen)
        val snapshot = capture.snapshot()!!
        assertEquals("COMPLETE", snapshot.outcome)
        assertEquals(33, snapshot.rows.size)
        assertEquals(-10011, snapshot.rows[0].raw)
        assertEquals("SENTINEL", snapshot.rows[1].status)
        assertEquals("ERROR", snapshot.rows[2].status)
        assertNull(snapshot.rows[2].raw)
        assertTrue(snapshot.rows[2].detail.contains("SecurityException: OEM denied"))
        assertTrue(snapshot.rows.all { it.receivedEpochMs != null && it.durationMs != null })
        assertTrue(capture.summary().contains("freshness"))
        assertTrue(decodeAdasTargets(snapshot).isEmpty())
    }

    @Test fun `deadline keeps blocked call quarantined and records every unattempted key until late return`() {
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1); val released = CountDownLatch(1)
        val calls = AtomicInteger(); val releases = AtomicInteger()
        val capture = AdasTargetCapture(timeoutMs = 40, release = { releases.incrementAndGet(); released.countDown() })
        try {
            assertTrue(capture.start(Variant.NEW, "fixture") { calls.incrementAndGet(); entered.countDown(); unblock.await(); 1 })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            waitFor { capture.snapshot()?.outcome == "TIMED_OUT" }
            assertTrue(capture.isBusy())
            repeat(10) { assertFalse(capture.start(null, "must not queue") { error("No second reader") }) }
            assertEquals(1, calls.get()); assertEquals(0, releases.get())
            val snapshot = capture.snapshot()!!
            assertEquals("PENDING", snapshot.rows.first().status)
            assertEquals(32, snapshot.rows.count { it.status == "NOT_ATTEMPTED" })
            assertTrue(decodeAdasTargets(snapshot).isEmpty())
        } finally { unblock.countDown() }
        assertTrue(released.await(1, TimeUnit.SECONDS))
        assertFalse(capture.isBusy())
        assertEquals("TIMED_OUT", capture.snapshot()!!.outcome)
        assertEquals("LATE_RAW", capture.snapshot()!!.rows.first().status)
        assertEquals(1, releases.get()); assertEquals(1, calls.get())
    }

    @Test fun `cancellation stops next property and late result cannot revive displayed targets`() {
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
        val calls = AtomicInteger()
        val capture = AdasTargetCapture()
        try {
            assertTrue(capture.start(Variant.NEW, "fixture") { calls.incrementAndGet(); entered.countDown(); unblock.await(); 120 })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            capture.cancel("Activity hidden")
            assertEquals("CANCELLED", capture.snapshot()!!.outcome)
            assertTrue(capture.isBusy())
            assertFalse(capture.start(null, "refused") { 0 })
        } finally { unblock.countDown() }
        waitFor { !capture.isBusy() }
        assertEquals(1, calls.get())
        assertEquals(120, capture.snapshot()!!.rows.first().raw)
        assertTrue(decodeAdasTargets(capture.snapshot()!!).isEmpty())
        assertEquals(32, capture.snapshot()!!.rows.count { it.status == "NOT_ATTEMPTED" })
    }

    @Test fun `last reply crossing logical deadline cannot complete before watchdog is scheduled`() {
        val clock = AtomicLong(0)
        val capture = AdasTargetCapture(monotonicMs = { clock.get() })
        assertTrue(capture.start(Variant.NEW, "fixture") { property ->
            if (property == AdasTargetProperties.LKS_MODE) clock.set(15_001)
            1
        })
        waitFor { !capture.isBusy() }
        assertEquals("TIMED_OUT", capture.snapshot()!!.outcome)
        assertEquals("LATE_RAW", capture.snapshot()!!.rows.last().status)
        assertTrue(decodeAdasTargets(capture.snapshot()!!).isEmpty())
    }

    @Test fun `retired watchdog identity cannot stop a replacement capture`() {
        val capture = AdasTargetCapture()
        assertTrue(capture.start(null, "first") { 0 })
        waitFor { !capture.isBusy() }
        val retiredId = capture.snapshot()!!.id
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
        try {
            assertTrue(capture.start(null, "replacement") { entered.countDown(); unblock.await(); 0 })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val stop = AdasTargetCapture::class.java.getDeclaredMethod("stop", String::class.java, String::class.java, String::class.java)
                .apply { isAccessible = true }
            stop.invoke(capture, "TIMED_OUT", "Retired watchdog already admitted", retiredId)
            assertEquals("RUNNING", capture.snapshot()!!.outcome)
            assertNotEquals(retiredId, capture.snapshot()!!.id)
        } finally { capture.cancel(); unblock.countDown() }
        waitFor { !capture.isBusy() }
    }
}
