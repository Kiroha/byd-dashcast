package com.byd.dashcast.clusterlive

import org.junit.Assert.*
import org.junit.Test

class ClusterRestartLeaseTest {
    private val round = "a".repeat(32)
    @Test fun `consent consumes once after revalidation and persistence while keeping admission closed`() {
        val lease = ClusterRestartLease { 10L }
        assertEquals(round, lease.issue(round))
        val steps = mutableListOf<String>()
        lease.consume(round, { steps += "verify" }, { steps += "persist" })
        assertEquals(listOf("verify", "persist"), steps)
        assertTrue(lease.held())
        lease.cancel(round); assertTrue(lease.held())
        assertThrows(IllegalStateException::class.java) { lease.consume(round, { fail() }, { fail() }) }
        lease.recovered(); assertFalse(lease.held())
    }
    @Test fun `expired foreign or clock reversed confirmation never reaches files or persistence`() {
        for (fault in 0..2) {
            var now = 100L; val lease = ClusterRestartLease { now }; lease.issue(round)
            if (fault == 0) now += 120001 else if (fault == 1) now--
            assertThrows(IllegalStateException::class.java) {
                lease.consume(if (fault == 2) "b".repeat(32) else round, { fail() }, { fail() })
            }
            assertEquals(fault == 2, lease.held())
        }
    }
    @Test fun `changed storage proof refuses dispatch but permits explicit cancellation`() {
        for (reason in listOf("original reappeared", "different inode", "hash changed", "symlink", "other owned RCC", "unreaped helper")) {
            val lease = ClusterRestartLease { 0L }; lease.issue(round)
            assertThrows(IllegalStateException::class.java) { lease.consume(round, { error(reason) }, { fail() }) }
            assertTrue(lease.held()); lease.cancel(round); assertFalse(lease.held())
        }
    }
    @Test fun `failed durable intent consumes permission and cannot be retried or cancelled`() {
        val lease = ClusterRestartLease { 0L }; lease.issue(round)
        assertThrows(IllegalStateException::class.java) { lease.consume(round, {}, { error("fsync failed") }) }
        lease.cancel(round); assertTrue(lease.held())
        assertThrows(IllegalStateException::class.java) { lease.consume(round, {}, {}) }
        assertThrows(IllegalStateException::class.java) { lease.issue("b".repeat(32)) }
    }
    @Test fun `lost unconsumed reply expires but consumed round never expires`() {
        var now = 0L; val lease = ClusterRestartLease { now }; lease.issue(round)
        now = 120001L; assertFalse(lease.held()); lease.issue("b".repeat(32))
        lease.consume("b".repeat(32), {}, {}); now += 999999L; assertTrue(lease.held())
        assertThrows(IllegalStateException::class.java) { lease.issue(round) }
    }
    @Test fun `cancellation invalidates old popup before a new round`() {
        val lease = ClusterRestartLease { 0L }; lease.issue(round); lease.cancel("b".repeat(32)); assertTrue(lease.held())
        lease.cancel(round); lease.issue("b".repeat(32))
        assertThrows(IllegalStateException::class.java) { lease.consume(round, { fail() }, { fail() }) }
    }
}
