package com.byd.dashcast.satellite

import android.os.SystemClock
import org.json.JSONObject

/** Process-local scalar evidence only. Never accepts signalling, identifiers or exception text. */
object SatelliteVideoDiagnostics {
    enum class Event {
        CONTROL_CONNECTED, CONTROL_CLOSED, VIEWER_STARTING, VIEWER_READY, VIEWER_CLOSED,
        VIEWER_UNAVAILABLE, OFFER_RECEIVED, ANSWER_QUEUED, ICE_RECEIVED, ICE_QUEUED,
        TRACK_RECEIVED, MEDIA_CONNECTED, FRAMES_DECODED, SENDER_STOPPED,
        SCRIPT_ERROR, BOOTSTRAP_TIMEOUT, WEBVIEW_UNAVAILABLE, PAGE_LOAD_FAILED, RENDERER_GONE,
        PLAYBACK_FAILED, CONNECTION_LOST, VIDEO_TIMEOUT, STATS_FAILED, SIGNALLING_OVERFLOW,
        NEGOTIATION_FAILED, OTHER_FAILURE,
    }
    internal class Timeline(private val clock: () -> Long = SystemClock::elapsedRealtime) {
        private data class Entry(val at: Long, val event: Event)
        private val entries = ArrayDeque<Entry>()
        private val counts = IntArray(Event.entries.size)
        @Synchronized fun record(event: Event, recent: Boolean = true) {
            counts[event.ordinal] = (counts[event.ordinal] + 1).coerceAtMost(1_000_000)
            if (recent) {
                if (entries.size == 32) entries.removeFirst()
                entries.addLast(Entry(clock(), event))
            }
        }
        @Synchronized fun summary(): String {
            val now = clock()
            return "Process-local history, newest first; queued replies do not confirm transmission. " +
                "Prior decoded frames do not confirm the current image.\n" +
                Event.entries.filter { counts[it.ordinal] > 0 }
                    .joinToString(" ") { "${it.name.lowercase()}=${counts[it.ordinal]}" } + "\n" +
                entries.toList().asReversed().joinToString("\n") { "${((now - it.at).coerceAtLeast(0) / 1000)}s ago: ${it.event.name.lowercase()}" }
        }
    }
    /** Fixed fields only; payload keys/strings are never retained or rendered. */
    internal data class Observation(
        val counters: Map<String, Long?>,
        val codec: String?,
        val statsState: String,
        val trackReceived: Boolean,
        val mediaConnected: Boolean,
        val decoded: Boolean,
    ) {
        fun summary(): String = "codec=${codec ?: "?"} stats=$statsState " +
            "track=$trackReceived media_connected=$mediaConnected decoded=$decoded " +
            counters.entries.joinToString(" ") { "${it.key}=${it.value ?: "?"}" }

        companion object {
            private val counterNames = listOf("bytesReceived", "packetsReceived", "packetsLost",
                "framesReceived", "framesDecoded", "keyFramesDecoded", "framesDropped",
                "qualityTotal", "qualityDropped", "webkitDecoded")
            private val ageNames = listOf("startupAgeMs", "answerAgeMs", "decodedAgeMs", "statsPendingAgeMs", "statsCompletedAgeMs")
            private val codecs = setOf("H264", "VP8", "VP9", "AV1")
            private val states = setOf("not_requested", "pending", "timed_out", "completed", "failed")
            fun parse(raw: String): Observation? {
                if (raw.length > 2048) return null
                val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
                fun number(name: String, max: Long): Long? {
                    val value = json.opt(name) as? Number ?: return null
                    val double = value.toDouble()
                    return if (double.isFinite() && double >= 0 && double <= max && double % 1.0 == 0.0)
                        double.toLong() else null
                }
                val state = (json.opt("statsState") as? String)?.takeIf { it in states } ?: return null
                fun flag(name: String) = json.opt(name) as? Boolean
                val track = flag("trackReceived") ?: return null
                val connected = flag("mediaConnected") ?: return null
                val decoded = flag("decoded") ?: return null
                return Observation(counterNames.associateWith { number(it, 9_000_000_000_000L) } +
                    ageNames.associateWith { number(it, 86_400_000L) },
                    (json.opt("codec") as? String)?.takeIf { it in codecs }, state, track, connected, decoded)
            }
        }
    }
    internal enum class End { REPLACED, VIEWER_HIDDEN, VIEWER_RELEASED, CONTROL_RESET,
        SENDER_STOPPED, VIDEO_TIMEOUT, PLAYBACK_FAILED, CONNECTION_LOST, NEGOTIATION_FAILED, OTHER_FAILURE }
    internal class Attempts(private val clock: () -> Long = SystemClock::elapsedRealtime) {
        private data class Attempt(val number: Long, val at: Long, var lastAt: Long? = null,
            var observation: Observation? = null, var end: End? = null)
        private val attempts = ArrayDeque<Attempt>()
        private var next = 0L
        @Synchronized fun begin(): Long {
            attempts.lastOrNull()?.takeIf { it.end == null }?.end = End.REPLACED
            if (attempts.size == 8) attempts.removeFirst()
            return (++next).also { attempts.addLast(Attempt(it, clock())) }
        }
        @Synchronized fun observe(number: Long, observation: Observation) {
            val active = attempts.lastOrNull()?.takeIf { it.number == number && it.end == null } ?: return
            val now = clock()
            if (active.lastAt?.let { now - it < 900 } == true) return
            active.observation = observation
            active.lastAt = now
        }
        @Synchronized fun end(number: Long, end: End) {
            attempts.lastOrNull()?.takeIf { it.number == number && it.end == null }?.end = end
        }
        @Synchronized fun summary(): String {
            val now = clock()
            return "Receiver observations, newest attempt first; ? means unavailable. " +
                "Historical decode counters do not prove current display or hardware decoding.\n" +
                attempts.toList().asReversed().joinToString("\n") { attempt ->
                    "attempt=${attempt.number} age_s=${(now - attempt.at).coerceAtLeast(0) / 1000} " +
                        "end=${attempt.end?.name?.lowercase() ?: "active"} " +
                        "sample_age_ms=${attempt.lastAt?.let { (now - it).coerceAtLeast(0) } ?: "?"} " +
                        (attempt.observation?.summary() ?: "no observation")
                }
        }
    }
    @Volatile private var providerVersion: String? = null
    internal fun providerVersion(value: String?) {
        providerVersion = value?.takeIf { it.length <= 40 && it.matches(Regex("[0-9]+(?:\\.[0-9]+){0,7}")) }
    }
    private val attempts = Attempts()
    internal fun beginAttempt(): Long = attempts.begin()
    internal fun observe(attempt: Long, observation: Observation) = attempts.observe(attempt, observation)
    internal fun endAttempt(attempt: Long, end: End) = attempts.end(attempt, end)
    private val timeline = Timeline()
    fun record(event: Event, recent: Boolean = true) = timeline.record(event, recent)
    fun summary(): String = timeline.summary() + "\nWebView version=${providerVersion ?: "?"}\n" + attempts.summary()
    fun failure(code: String) = record(when (code) {
        "script_error" -> Event.SCRIPT_ERROR
        "bootstrap_timeout" -> Event.BOOTSTRAP_TIMEOUT
        "webrtc_unavailable", "webview_create_failed", "webview_linkage_failed" -> Event.WEBVIEW_UNAVAILABLE
        "page_load_failed" -> Event.PAGE_LOAD_FAILED
        "renderer_gone" -> Event.RENDERER_GONE
        "playback_failed" -> Event.PLAYBACK_FAILED
        "connection_lost" -> Event.CONNECTION_LOST
        "video_timeout" -> Event.VIDEO_TIMEOUT
        "stats_failed" -> Event.STATS_FAILED
        "signalling_overflow" -> Event.SIGNALLING_OVERFLOW
        "negotiation_failed" -> Event.NEGOTIATION_FAILED
        else -> Event.OTHER_FAILURE
    })
    fun progress(code: String) {
        val event = when (code) {
            "track_received" -> Event.TRACK_RECEIVED
            "media_connected" -> Event.MEDIA_CONNECTED
            "frames_decoded" -> Event.FRAMES_DECODED
            else -> return
        }
        record(event)
    }
}
