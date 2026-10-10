package com.byd.dashcast.satellite

import org.junit.Assert.*
import org.junit.Test
import com.byd.dashcast.satellite.SatelliteStatusTracker.Connection
import com.byd.dashcast.satellite.SatelliteStatusTracker.Guidance

class SatelliteStatusTrackerTest {
    @Test fun `enabled preference alone is never a listening or authenticated connection`() {
        val state = SatelliteStatusTracker()
        assertEquals(Connection.DISABLED, state.snapshot(false, false, 0).connection)
        assertEquals(Connection.STARTING, state.snapshot(true, true, 0).connection)
        val owner = state.begin()
        assertEquals(Connection.STARTING, state.snapshot(true, true, 0).connection)
        state.listening(owner)
        assertEquals(SatelliteStatusTracker.Snapshot(Connection.WAITING, Guidance.WAITING), state.snapshot(true, true, 0))
    }

    @Test fun `authentication does not imply guidance and source selection remains independent`() {
        val state = SatelliteStatusTracker(); val owner = state.begin()
        state.connected(owner, "authenticated-peer")
        assertEquals(SatelliteStatusTracker.Snapshot(Connection.CONNECTED, Guidance.WAITING), state.snapshot(true, true, 0))
        assertEquals(Guidance.DISABLED, state.snapshot(true, false, 0).guidance)
    }

    @Test fun `source age reaches the exact protocol deadline and fresh data can resume`() {
        val state = SatelliteStatusTracker(); val owner = state.begin()
        state.connected(owner, "peer")
        state.guidance(owner, "peer", 1_000, 1_500)
        assertEquals(Guidance.ACTIVE, state.snapshot(true, true, 5_499).guidance)
        assertEquals(Guidance.EXPIRED, state.snapshot(true, true, 5_500).guidance)
        assertEquals(Connection.CONNECTED, state.snapshot(true, true, 20_000).connection)
        state.expired(owner, "peer")
        state.guidance(owner, "peer", 21_000, 0)
        assertEquals(Guidance.ACTIVE, state.snapshot(true, true, 21_000).guidance)
    }

    @Test fun `source change stop and disconnect discard the previous guidance`() {
        val state = SatelliteStatusTracker(); val owner = state.begin()
        state.connected(owner, "peer"); state.guidance(owner, "peer", 0, 0)
        state.sourceChanged()
        state.expired(owner, "peer")
        assertEquals(Guidance.WAITING, state.snapshot(true, true, 7_000).guidance)
        state.guidance(owner, "peer", 8_000, 0); state.stopped(owner, "peer")
        assertEquals(Guidance.WAITING, state.snapshot(true, true, 8_000).guidance)
        state.guidance(owner, "peer", 9_000, 0); state.disconnected(owner, "peer")
        assertEquals(SatelliteStatusTracker.Snapshot(Connection.WAITING, Guidance.WAITING), state.snapshot(true, true, 9_000))
    }

    @Test fun `revocation immediately removes authentication and fences already queued callbacks`() {
        val state = SatelliteStatusTracker(); val owner = state.begin()
        state.connected(owner, "peer"); state.guidance(owner, "peer", 0, 0)
        state.revoked()
        state.connected(owner, "peer"); state.guidance(owner, "peer", 0, 0)
        assertEquals(SatelliteStatusTracker.Snapshot(Connection.WAITING, Guidance.WAITING), state.snapshot(true, true, 0))
        assertEquals(SatelliteStatusTracker.Snapshot(Connection.DISABLED, Guidance.DISABLED), state.snapshot(false, true, 0))
        val replacement = state.begin(); state.connected(replacement, "new-peer")
        assertEquals(Connection.CONNECTED, state.snapshot(true, true, 0).connection)
    }

    @Test fun `failure survives teardown and a real retry can recover`() {
        val state = SatelliteStatusTracker(); val owner = state.begin()
        state.failed(owner); state.listening(owner); state.connected(owner, "late-peer"); state.end(owner)
        assertEquals(Connection.UNAVAILABLE, state.snapshot(true, true, 0).connection)
        assertEquals(Connection.DISABLED, state.snapshot(false, true, 0).connection)
        val retry = state.begin(); state.listening(retry)
        assertEquals(Connection.WAITING, state.snapshot(true, true, 0).connection)
    }

    @Test fun `readiness deadlines cannot fail a listening or replacement transport`() {
        val state = SatelliteStatusTracker()
        val old = state.begin()
        state.listening(old)
        assertFalse(state.isStarting(old))
        assertFalse(state.failIfStarting(old))
        val current = state.begin()
        assertFalse(state.failIfStarting(old))
        assertTrue(state.isStarting(current))
        assertTrue(state.failIfStarting(current))
        assertFalse(state.isStarting(current))
        state.listening(current)
        state.connected(current, "late-peer")
        assertEquals(Connection.UNAVAILABLE, state.snapshot(true, true, 0).connection)
    }

    @Test fun `late events from an obsolete server or another peer cannot clear the new session`() {
        val state = SatelliteStatusTracker(); val old = state.begin()
        state.connected(old, "old-peer")
        val current = state.begin(); state.connected(current, "new-peer"); state.guidance(current, "new-peer", 0, 0)
        state.disconnected(old, "old-peer"); state.failed(old); state.end(old); state.connected(old, "old-peer")
        state.disconnected(current, "other-peer"); state.expired(current, "other-peer"); state.stopped(current, "other-peer")
        assertEquals(SatelliteStatusTracker.Snapshot(Connection.CONNECTED, Guidance.ACTIVE), state.snapshot(true, true, 0))
    }

    @Test fun `redundant foreground start failure cannot replace a healthy server status`() {
        val state = SatelliteStatusTracker(); state.startFailed()
        assertEquals(Connection.UNAVAILABLE, state.snapshot(true, true, 0).connection)
        val owner = state.begin()
        state.startFailed()
        assertEquals(Connection.STARTING, state.snapshot(true, true, 0).connection)
        state.listening(owner); state.connected(owner, "peer")
        state.startFailed()
        assertEquals(Connection.CONNECTED, state.snapshot(true, true, 0).connection)
        state.revoked(); state.startFailed()
        assertEquals(Connection.UNAVAILABLE, state.snapshot(true, true, 0).connection)
    }

    @Test fun `public snapshots never contain peer identity or payload fields`() {
        val state = SatelliteStatusTracker(); val owner = state.begin()
        state.connected(owner, "private-peer-marker"); state.guidance(owner, "private-peer-marker", 0, 0)
        val snapshot = state.snapshot(true, true, 0)
        assertFalse(snapshot.toString().contains("private-peer-marker"))
        assertEquals(setOf("connection", "guidance"), snapshot.javaClass.declaredFields.map { it.name }.toSet())
    }
}
