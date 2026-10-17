package com.byd.dashcast.satellite

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject

/** Manual, sender-reported evidence only. Never stores incoming JSON, content or identifiers in reports. */
internal object SatelliteRemoteVideoDiagnostics {
    const val MAX_BYTES = 16_384
    const val MAX_AGE_MS = 86_400_000L
    const val MAX_ORDINAL = 1_000_000L
    enum class Event { CONTROL_CONNECTED, CONTROL_CLOSED, VIEWER_READY, VIEWER_CLOSED, LEGACY_VIEWER_ERROR, CONSENT_REQUESTED, CONSENT_CANCELLED, SHARE_STARTED, PROJECTION_CREATED, WAITING_VIEWER, NATIVE_INITIALIZING, ENCODER_CHECK, HARDWARE_UNAVAILABLE, TEXTURE_INITIALIZING, DISPLAY_INITIALIZING, CAPTURE_INITIALIZED, NEGOTIATING, OFFER_SENT, ANSWER_RECEIVED, ANSWER_APPLIED, ICE_SENT, ICE_RECEIVED, MEDIA_CONNECTED, FRAMES_ENCODED, RECEIVER_ERROR, NEGOTIATION_FAILED, CAPTURE_UNAVAILABLE, NATIVE_UNAVAILABLE, CAPTURE_REVOKED, CAPTURE_LOCKED, CAPTURE_HIDDEN, CONTROL_UNAVAILABLE, MEDIA_LOST, SIGNALLING_OVERFLOW, CONNECTION_TIMEOUT, ENCODER_STALLED, STATS_TIMEOUT, STOPPED, OTHER_FAILURE }
    enum class Cause { NONE, SECURITY, STATE, ARGUMENT, LINKAGE, OTHER }
    enum class State { STOPPED, STARTING, WAITING_VIEWER, NEGOTIATING, CONNECTED_WAITING_FRAMES, STREAMING, CONTROL_UNAVAILABLE, RECEIVER_ADDRESS_UNSUPPORTED, CAPTURE_UNAVAILABLE, CONSENT_REQUIRED, NATIVE_UNAVAILABLE, CAPTURE_REVOKED, CAPTURE_LOCKED, CAPTURE_PAUSED, CAPTURE_HIDDEN, MEDIA_CONNECTION_LOST, SIGNALLING_OVERFLOW, NEGOTIATION_FAILED, VIDEO_PROTOCOL_FAILED, RECEIVER_VIDEO_FAILED, ICE_FAILED, MEDIA_CONNECTION_TIMEOUT, ENCODER_STALLED, STATS_TIMEOUT, OTHER_FAILURE }
    data class Entry(val event: Event, val cause: Cause, val share: Long, val ageMs: Long)
    data class Snapshot(val request: Long, val appVersionCode: Int, val state: State, val captureBusy: Boolean,
        val share: Long, val snapshotAgeMs: Long, val counts: List<Pair<Event, Int>>, val recent: List<Entry>, val details: List<SenderAttempt>? = null)
    data class SenderAttempt(val attempt: Long, val share: Long, val ageMs: Long, val sampleAgeMs: Long?,
        val ended: Boolean, val codec: String?, val counters: Map<String, Long?>, val h264: List<H264Observation>? = null)
    data class H264Observation(val ageMs: Long, val declaredKey: Boolean, val width: Long?, val height: Long?,
        val framing: String, val inspection: String, val presence: Map<String, Boolean?>, val metadata: Map<String, Long?>)

    fun parse(json: JSONObject): Snapshot {
        require(json.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        keys(json, setOf("type", "schema", "request", "snapshotAgeMs", "appVersionCode", "state", "captureBusy", "share", "counts", "recent") + if (json.has("details")) setOf("details") else emptySet())
        require(json.get("type") == "diagnostics.video")
        SatelliteProtocol.integer(json, "schema", 1, 1)
        val request = SatelliteProtocol.integer(json, "request", 1, MAX_ORDINAL)
        val version = SatelliteProtocol.integer(json, "appVersionCode", 1, MAX_ORDINAL).toInt()
        val state = value<State>(json, "state")
        val busy = json.get("captureBusy"); require(busy is Boolean)
        val share = SatelliteProtocol.integer(json, "share", 0, MAX_ORDINAL)
        val age = SatelliteProtocol.integer(json, "snapshotAgeMs", 0, MAX_AGE_MS)
        val counts = json.getJSONObject("counts"); require(counts.length() <= 32)
        val totals = counts.keys().asSequence().map { key ->
            val event = Event.entries.singleOrNull { it.name.lowercase() == key } ?: throw IllegalArgumentException()
            event to SatelliteProtocol.integer(counts, key, 1, MAX_ORDINAL).toInt()
        }.toList()
        val recent = json.getJSONArray("recent"); require(recent.length() <= 32)
        var previousAge = 0L
        val rows = (0 until recent.length()).map {
            val row = recent.getJSONObject(it)
            keys(row, setOf("event", "cause", "share", "ageMs"))
            val event = value<Event>(row, "event"); val cause = value<Cause>(row, "cause")
            val ordinal = SatelliteProtocol.integer(row, "share", 0, share)
            previousAge = SatelliteProtocol.integer(row, "ageMs", previousAge, MAX_AGE_MS)
            Entry(event, cause, ordinal, previousAge)
        }
        return Snapshot(request, version, state, busy, share, age, totals, rows,
            if (json.has("details")) parseDetails(json.getJSONObject("details"), share) else null)
    }
    private val senderCounters = listOf("bytesSent", "packetsSent", "framesEncoded", "keyFramesEncoded", "pliCount", "nackCount", "firCount")
    private fun parseDetails(json: JSONObject, share: Long): List<SenderAttempt> {
        keys(json, setOf("schema", "attempts"))
        val schema = SatelliteProtocol.integer(json, "schema", 1, 2)
        val rows = json.getJSONArray("attempts"); require(rows.length() <= 4)
        var previousAttempt = MAX_ORDINAL + 1
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            keys(row, setOf("attempt", "share", "ageMs", "sampleAgeMs", "ended", "codec") + senderCounters + if (schema == 2L) setOf("h264") else emptySet())
            val attempt = SatelliteProtocol.integer(row, "attempt", 1, previousAttempt - 1)
            previousAttempt = attempt
            val session = SatelliteProtocol.integer(row, "share", 0, share)
            val age = SatelliteProtocol.integer(row, "ageMs", 0, MAX_AGE_MS)
            fun nullable(key: String, max: Long): Long? = if (row.get(key) === JSONObject.NULL) null else SatelliteProtocol.integer(row, key, 0, max)
            val sampleAge = nullable("sampleAgeMs", age)
            val ended = row.get("ended"); require(ended is Boolean)
            val codec = row.get("codec"); require(codec === JSONObject.NULL || codec in setOf("h264", "vp8", "vp9", "av1"))
            val counters = senderCounters.associateWith { nullable(it, 9_000_000_000_000L) }
            val frames = counters["framesEncoded"]; val keys = counters["keyFramesEncoded"]
            require(frames == null || keys == null || keys <= frames)
            require(sampleAge != null || (codec === JSONObject.NULL && counters.values.all { it == null }))
            SenderAttempt(attempt, session, age, sampleAge, ended, codec as? String, counters,
                if (schema == 2L) parseH264(row.getJSONArray("h264"), age) else null)
        }
    }
    private fun parseH264(rows: org.json.JSONArray, age: Long): List<H264Observation> {
        require(rows.length() <= 4)
        var previousAge = age
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            keys(row, setOf("ageMs", "declaredKey", "width", "height", "framing", "inspection", "sps", "pps", "idr", "profileIdc", "constraintFlags", "levelIdc"))
            previousAge = SatelliteProtocol.integer(row, "ageMs", 0, previousAge)
            require(row.get("declaredKey") is Boolean)
            if (index > 0) require(row.get("declaredKey") == true)
            fun number(key: String, max: Long): Long? = if (row.get(key) === JSONObject.NULL) null else SatelliteProtocol.integer(row, key, 0, max)
            listOf("width", "height").forEach { require(number(it, 8192)?.let { n -> n > 0 } != false) }
            val framing = row.get("framing"); val inspection = row.get("inspection")
            require(framing in setOf("annex_b", "unknown") && inspection in setOf("complete", "truncated"))
            listOf("sps", "pps", "idr").forEach { key ->
                val value = row.get(key); require(value === JSONObject.NULL || value is Boolean)
                require(framing != "unknown" || value === JSONObject.NULL)
                require(value != false || inspection == "complete")
            }
            val metadata = listOf("profileIdc", "constraintFlags", "levelIdc").map { number(it, 255) }
            require(metadata.all { it == null } || (metadata.all { it != null } && row.get("sps") == true))
            H264Observation(previousAge, row.getBoolean("declaredKey"), number("width", 8192), number("height", 8192),
                framing as String, inspection as String, listOf("sps", "pps", "idr").associateWith { row.get(it) as? Boolean },
                listOf("profileIdc", "constraintFlags", "levelIdc").zip(metadata).toMap())
        }
    }
    private inline fun <reified T : Enum<T>> value(json: JSONObject, key: String): T =
        enumValues<T>().singleOrNull { it.name.lowercase() == json.get(key) } ?: throw IllegalArgumentException()
    private fun keys(json: JSONObject, expected: Set<String>) { require(json.keys().asSequence().toSet() == expected) }

    /** The enclosing preference monitor also fences authentication, disable and token revocation. */
    internal class Ledger(private val clock: () -> Long = SystemClock::elapsedRealtime) {
        private var peer: String? = null
        private var session: String? = null
        private var lastRequest = 0L
        private var receivedSession: String? = null
        private var receivedAt = 0L
        private var snapshot: Snapshot? = null
        fun connected(owner: String, identity: String?) {
            if (identity == null || peer != identity) clear()
            peer = identity; session = owner; lastRequest = 0
        }
        fun disconnected(owner: String) { if (session == owner) session = null }
        fun clear() { peer = null; session = null; lastRequest = 0; receivedSession = null; snapshot = null }
        fun accept(owner: String, value: Snapshot): Boolean {
            if (owner != session || value.request <= lastRequest) return false
            lastRequest = value.request; receivedAt = clock(); receivedSession = owner; snapshot = value
            return true
        }
        fun summary(): String {
            val value = snapshot
            val age = clock() - receivedAt
            if (value == null || age !in 0 until MAX_AGE_MS) {
                snapshot = null
                return "No manually received DashSat diagnostics in this process (24-hour retention)."
            }
            return buildString {
                append("Sender-reported manual snapshot; historical evidence, not live status or image confirmation.\n")
                append("receivedAgeMs=").append(age).append(" snapshotAgeAtReceiptMs=").append(value.snapshotAgeMs)
                append(" receiptConnection=").append(if (receivedSession == session) "still_connected" else "disconnected_or_replaced").append('\n')
                append("senderVersionCode=").append(value.appVersionCode).append(" state=").append(value.state.name.lowercase())
                append(" captureBusy=").append(value.captureBusy).append(" share=").append(value.share).append('\n')
                append("Ages saturate at 86400000 ms (at least 24 hours); counts and share ordinals at 1000000 are lower bounds and may merge attempts.\n")
                append("Counters are process totals (up to 32 selected event types); share numbers are local ordinals.\n")
                append(value.counts.joinToString(" ") { "${it.first.name.lowercase()}=${it.second}" }).append('\n')
                if (value.details?.any { it.h264 != null } == true) append("Encoded H264 observations inspect bounded sender bytes; SDK dimensions are not SPS dimensions. SPS metadata is first observed, not linked to an IDR. No receiver decoding is established.\n")
                value.details?.forEach { row ->
                    append("senderAttempt=").append(row.attempt).append(" share=").append(row.share)
                    append(" ageAtCaptureMs=").append(row.ageMs).append(" sampleAgeAtCaptureMs=").append(row.sampleAgeMs ?: "?")
                    append(" ended=").append(row.ended).append(" codec=").append(row.codec ?: "?").append(' ')
                    append(row.counters.entries.joinToString(" ") { "${it.key}=${it.value ?: "?"}" }).append('\n')
                    row.h264?.forEach { item ->
                        append("encodedH264 ageAtCaptureMs=").append(item.ageMs).append(" declaredKey=").append(item.declaredKey)
                        append(" sdkDimensions=").append(item.width ?: "?").append('x').append(item.height ?: "?")
                        append(" framing=").append(item.framing).append(" inspection=").append(item.inspection).append(' ')
                        append(item.presence.entries.joinToString(" ") { "${it.key}=${it.value ?: "?"}" }).append(' ')
                        append(item.metadata.entries.joinToString(" ") { "${it.key}=${it.value ?: "?"}" }).append('\n')
                    }
                }
                value.recent.forEach { row ->
                    append("eventAgeAtCaptureMs=").append(row.ageMs).append(" share=").append(row.share)
                    append(" event=").append(row.event.name.lowercase()).append(" cause=").append(row.cause.name.lowercase()).append('\n')
                }
            }
        }
    }
    private val ledger = Ledger()
    fun connected(session: String, identity: String?) = synchronized(SatellitePrefs) { ledger.connected(session, identity) }
    fun disconnected(session: String) = synchronized(SatellitePrefs) { ledger.disconnected(session) }
    fun clear() = synchronized(SatellitePrefs) { ledger.clear() }
    fun receive(context: Context, expectedToken: String, owner: String, json: JSONObject): Boolean = synchronized(SatellitePrefs) {
        if (!SatellitePrefs.isEnabled(context) || !SatellitePrefs.tokenMatches(expectedToken, SatellitePrefs.token(context))) return@synchronized false
        ledger.accept(owner, parse(json))
    }
    fun summary(): String = synchronized(SatellitePrefs) { ledger.summary() }
}
