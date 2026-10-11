package com.byd.dashcast.media

/** Passive, scalar-only journal. Never retains media content, identities or exception messages. */
internal class MediaDiagnosticStore {
    private var epoch = 0L
    private var listener = false
    private var observer = "STOPPED"
    private var authorization = "UNKNOWN"
    private var observerFailure = "NONE"
    private var playing = 0
    private var sessions = 0
    private var usable = 0
    private var selected = false
    private var failures = 0
    private var retryMs = 0L
    private var stage = "NONE"
    private var result = "NONE"
    private var sdk: Int? = null
    private var rejectedStage = "NONE"
    private var rejectedSdk: Int? = null
    private var outputFailure = "NONE"
    private var cleanupResult = "NONE"
    private var guidanceSuppressed = false
    @Synchronized fun guidance(value: Boolean) { guidanceSuppressed = value }
    @Synchronized fun begin(): Long { epoch++; observer = "STARTING"; sessions = 0; usable = 0; playing = 0; selected = false; return epoch }
    @Synchronized fun generation() = epoch
    @Synchronized fun listener(value: Boolean) { listener = value }
    @Synchronized fun stop(state: String = "STOPPED") { epoch++; observer = state; sessions = 0; usable = 0; playing = 0; selected = false; retryMs = 0 }
    @Synchronized fun waiting(delay: Long, attempts: Int) { observer = "RETRY_WAIT"; retryMs = delay; failures = attempts }
    @Synchronized fun busy() { observer = "WAITING_OWNER" }
    @Synchronized fun collection(generation: Long, count: Int, valid: Int, present: Boolean, playingCount: Int = 0) {
        if (generation != epoch) return
        observer = "OBSERVING"; authorization = "GRANTED"; sessions = count.coerceIn(0, 32)
        usable = valid.coerceIn(0, 32); playing = playingCount.coerceIn(0, 32); selected = present; failures = 0; retryMs = 0
    }
    @Synchronized fun failure(generation: Long, denied: Boolean) {
        if (generation == epoch) {
            authorization = if (denied) "DENIED" else "UNKNOWN"
            observerFailure = if (denied) "SECURITY" else "OBSERVATION_EXCEPTION"
        }
    }
    @Synchronized fun output(at: String, rc: Int?) {
        if (at == "EXCEPTION") {
            rejectedStage = stage; rejectedSdk = null; outputFailure = "TRANSPORT_EXCEPTION"
            sdk = null
            return
        }
        stage = at; sdk = rc
        if (rc != null && rc != 0 && !at.startsWith("CLEAR_")) {
            rejectedStage = at; rejectedSdk = rc; outputFailure = "SDK_REJECTED"
        }
    }
    @Synchronized fun result(value: MediaWriteResult) {
        result = value.name
        if (value == MediaWriteResult.CLEARED || value == MediaWriteResult.CLEAR_FAILED ||
            value == MediaWriteResult.NOTHING_TO_CLEAR) cleanupResult = value.name
    }
    @Synchronized fun summary(enabled: Boolean): String =
        "enabled=$enabled listenerConnected=$listener observer=$observer authorization=$authorization lastObserverFailure=$observerFailure " +
        "sessions=$sessions usableSessions=$usable playingSessions=$playing selected=$selected failures=$failures retryDelayMs=$retryMs clusterGuidanceSuppressed=$guidanceSuppressed " +
        "outputStage=$stage outputResult=$result cleanupResult=$cleanupResult sdkReturn=${sdk ?: "UNKNOWN"} lastOutputFailure=$outputFailure lastRejectedStage=$rejectedStage lastRejectedSdkReturn=${rejectedSdk ?: "UNKNOWN"} (acceptance is not render)"
}

object MediaDiagnostics {
    internal val store = MediaDiagnosticStore()
    fun initializationFailed() { store.stop("INITIALIZATION_FAILED") }
    fun summary(context: android.content.Context): String = store.summary(MediaPrefs.isEnabled(context))
}
