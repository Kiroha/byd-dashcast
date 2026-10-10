package com.byd.dashcast.satellite

import android.content.Context
import android.os.SystemClock

/** RAM-only facts from the receiver and accepted guidance. Snapshots contain no peer or payload data. */
internal open class SatelliteStatusTracker {
    enum class Connection { DISABLED, STARTING, WAITING, CONNECTED, UNAVAILABLE }
    enum class Guidance { DISABLED, WAITING, ACTIVE, EXPIRED }
    data class Snapshot(val connection: Connection, val guidance: Guidance)

    private var owner = 0L
    private var running = false
    private var listening = false
    private var failed = false
    private var authenticationAllowed = true
    private var session: String? = null
    private var receivedAt: Long? = null
    private var sourceAgeMs = 0L
    private var expired = false

    /** Every replacement transport owns a new generation; late callbacks cannot change its state. */
    @Synchronized fun begin(): Long {
        owner++
        running = true
        listening = false
        failed = false
        authenticationAllowed = true
        session = null
        clearGuidance()
        return owner
    }

    @Synchronized fun listening(owner: Long) {
        if (owns(owner) && authenticationAllowed) listening = true
    }

    @Synchronized fun isStarting(owner: Long): Boolean = owns(owner) && authenticationAllowed && !listening

    /** A deadline and the real listener callback race under the same owner/state monitor. */
    @Synchronized fun failIfStarting(owner: Long): Boolean {
        if (!isStarting(owner)) return false
        failed(owner)
        return true
    }

    @Synchronized fun connected(owner: Long, session: String) {
        if (!owns(owner) || !authenticationAllowed) return
        // Authentication itself also proves that the transport has bound its listener.
        listening = true
        this.session = session
        clearGuidance()
    }

    @Synchronized fun guidance(owner: Long, session: String, receivedAtMs: Long, ageMs: Long) {
        if (!owns(owner) || !authenticationAllowed || this.session != session) return
        require(ageMs in 0..SatelliteProtocol.MAX_AGE_MS)
        receivedAt = receivedAtMs
        sourceAgeMs = ageMs
        expired = false
    }

    @Synchronized fun stopped(owner: Long, session: String) {
        if (owns(owner) && this.session == session) clearGuidance()
    }

    @Synchronized fun expired(owner: Long, session: String) {
        if (owns(owner) && this.session == session && receivedAt != null) expired = true
    }

    @Synchronized fun disconnected(owner: Long, session: String) {
        if (!owns(owner) || this.session != session) return
        this.session = null
        clearGuidance()
    }

    @Synchronized fun failed(owner: Long) {
        if (!owns(owner)) return
        failed = true
        listening = false
        session = null
        clearGuidance()
    }

    @Synchronized fun startFailed() {
        // A redundant request cannot judge a service already starting or listening. Its own
        // generation-scoped failure callback reports real foreground, TLS and bind failures.
        if (!running || !authenticationAllowed) { failed = true; listening = false; session = null; clearGuidance() }
    }

    @Synchronized fun end(owner: Long) {
        if (this.owner != owner) return
        running = false
        listening = false
        session = null
        clearGuidance()
        // Keep a real start failure visible after the foreground service tears itself down.
    }

    @Synchronized fun revoked() {
        authenticationAllowed = false
        session = null
        clearGuidance()
    }

    @Synchronized fun sourceChanged() = clearGuidance()

    @Synchronized fun snapshot(enabled: Boolean, selected: Boolean, nowMs: Long): Snapshot {
        val connection = when {
            !enabled -> Connection.DISABLED
            failed -> Connection.UNAVAILABLE
            running && authenticationAllowed && session != null -> Connection.CONNECTED
            running && listening -> Connection.WAITING
            else -> Connection.STARTING
        }
        val received = receivedAt
        val guidance = when {
            !enabled || !selected -> Guidance.DISABLED
            connection != Connection.CONNECTED || received == null -> Guidance.WAITING
            expired || nowMs - received + sourceAgeMs >= SatelliteProtocol.NAV_TIMEOUT_MS -> Guidance.EXPIRED
            else -> Guidance.ACTIVE
        }
        return Snapshot(connection, guidance)
    }

    private fun owns(owner: Long) = this.owner == owner && running && !failed
    private fun clearGuidance() { receivedAt = null; sourceAgeMs = 0; expired = false }
}

/** The page samples current facts only while visible; receiver work continues independently. */
internal object SatelliteStatus : SatelliteStatusTracker() {
    fun snapshot(ctx: Context): Snapshot = snapshot(SatellitePrefs.isEnabled(ctx),
        SatellitePrefs.usesRemoteGuidance(ctx), SystemClock.elapsedRealtime())
}
