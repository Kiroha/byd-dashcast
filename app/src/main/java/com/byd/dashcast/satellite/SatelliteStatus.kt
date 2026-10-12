package com.byd.dashcast.satellite

import android.content.Context
import android.os.SystemClock
import com.byd.dashcast.hud.HudNavigationData

/** RAM-only facts from the receiver and accepted guidance. Snapshots contain no peer or payload data. */
internal open class SatelliteStatusTracker {
    enum class Connection { DISABLED, STARTING, WAITING, CONNECTED, UNAVAILABLE }
    enum class Guidance { DISABLED, WAITING, ACTIVE, EXPIRED }
    data class Snapshot(val connection: Connection, val guidance: Guidance, val failure: SatelliteReceiverFailure? = null)

    private var owner = 0L
    private var running = false
    private var listening = false
    private var failed = false
    private var failure: SatelliteReceiverFailure? = null
    private var authenticationAllowed = true
    private var session: String? = null
    private var receivedAt: Long? = null
    private var sourceAgeMs = 0L
    private var expired = false
    private data class InputDetails(val kind: String, val distanceMeters: Int, val exitKnown: Boolean,
                                    val outputAccepted: Boolean? = null)
    private var lastAcceptedInput: InputDetails? = null
    private var lastClear = "NONE"

    /** Every replacement transport owns a new generation; late callbacks cannot change its state. */
    @Synchronized fun begin(): Long {
        owner++
        running = true
        listening = false
        failed = false
        failure = null
        authenticationAllowed = true
        session = null
        lastAcceptedInput = null
        lastClear = "RECEIVER_START"
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
        failed(owner, SatelliteReceiverFailure.STARTUP_TIMEOUT)
        return true
    }

    @Synchronized fun connected(owner: Long, session: String) {
        if (!owns(owner) || !authenticationAllowed) return
        // Authentication itself also proves that the transport has bound its listener.
        listening = true
        this.session = session
        lastClear = "NEW_SESSION"
        clearGuidance()
    }

    @Synchronized fun guidance(owner: Long, session: String, receivedAtMs: Long, ageMs: Long,
                               data: HudNavigationData? = null) {
        if (!owns(owner) || !authenticationAllowed || this.session != session) return
        require(ageMs in 0..SatelliteProtocol.MAX_AGE_MS)
        receivedAt = receivedAtMs
        sourceAgeMs = ageMs
        expired = false
        lastClear = "NONE"
        data?.let {
            lastAcceptedInput = InputDetails(when {
                it.genericRoundaboutDirection != null -> "GENERIC_ROUNDABOUT"
                it.iconId in 25..44 -> "NUMBERED_ROUNDABOUT"
                else -> "OTHER_SUPPORTED_MANEUVER"
            }, it.distanceMeters, it.genericRoundaboutDirection == null && it.iconId in 25..44)
        }
    }

    @Synchronized fun outputAccepted(owner: Long, session: String, accepted: Boolean) {
        if (!owns(owner) || !authenticationAllowed || this.session != session || receivedAt == null) return
        lastAcceptedInput = lastAcceptedInput?.copy(outputAccepted = accepted)
    }

    @Synchronized fun stopped(owner: Long, session: String) {
        if (owns(owner) && this.session == session) { lastClear = "SOURCE_STOP"; clearGuidance() }
    }

    @Synchronized fun expired(owner: Long, session: String) {
        if (owns(owner) && this.session == session && receivedAt != null) { expired = true; lastClear = "EXPIRED" }
    }

    @Synchronized fun disconnected(owner: Long, session: String) {
        if (!owns(owner) || this.session != session) return
        this.session = null
        lastClear = "DISCONNECTED"
        clearGuidance()
    }

    @Synchronized fun failed(owner: Long, reason: SatelliteReceiverFailure = SatelliteReceiverFailure.CONTROL_TRANSPORT) {
        if (!owns(owner)) return
        failed = true
        failure = reason
        listening = false
        session = null
        lastClear = "RECEIVER_FAILED"
        clearGuidance()
    }

    @Synchronized fun startFailed() {
        // A redundant request cannot judge a service already starting or listening. Its own
        // generation-scoped failure callback reports real foreground, TLS and bind failures.
        if (!running || !authenticationAllowed) { failure = SatelliteReceiverFailure.FOREGROUND; failed = true; listening = false; session = null; clearGuidance() }
    }

    @Synchronized fun failureFor(owner: Long): SatelliteReceiverFailure? =
        failure.takeIf { this.owner == owner && failed }

    @Synchronized fun end(owner: Long) {
        if (this.owner != owner) return
        running = false
        listening = false
        session = null
        lastClear = "RECEIVER_STOPPED"
        clearGuidance()
        // Keep a real start failure visible after the foreground service tears itself down.
    }

    @Synchronized fun revoked() {
        authenticationAllowed = false
        session = null
        lastClear = "REVOKED"
        clearGuidance()
    }

    @Synchronized fun sourceChanged() { lastClear = "SOURCE_CHANGED"; clearGuidance() }

    /** Passive scalar report; the last accepted input is explicitly historical after a clear. */
    @Synchronized fun summary(enabled: Boolean, selected: Boolean, nowMs: Long): String {
        val state = snapshot(enabled, selected, nowMs)
        val last = lastAcceptedInput
        return "connection=${state.connection} guidance=${state.guidance} " +
            "lastAcceptedKind=${last?.kind ?: "NONE"} lastDistanceMeters=${last?.distanceMeters ?: "UNKNOWN"} " +
            "lastExitKnown=${last?.exitKnown ?: "UNKNOWN"} lastAnyOutputAccepted=${last?.outputAccepted ?: "UNKNOWN"} " +
            "clearReason=${if (state.guidance == Guidance.EXPIRED) "EXPIRED" else lastClear} " +
            "(input receipt and OEM acceptance do not prove physical rendering)"
    }

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
        return Snapshot(connection, guidance, failure.takeIf { connection == Connection.UNAVAILABLE })
    }

    private fun owns(owner: Long) = this.owner == owner && running && !failed
    private fun clearGuidance() { receivedAt = null; sourceAgeMs = 0; expired = false }
}

/** The page samples current facts only while visible; receiver work continues independently. */
internal object SatelliteStatus : SatelliteStatusTracker() {
    fun snapshot(ctx: Context): Snapshot = snapshot(SatellitePrefs.isEnabled(ctx),
        SatellitePrefs.usesRemoteGuidance(ctx), SystemClock.elapsedRealtime())
    fun summary(ctx: Context): String = summary(SatellitePrefs.isEnabled(ctx),
        SatellitePrefs.usesRemoteGuidance(ctx), SystemClock.elapsedRealtime())
}
