package com.byd.dashcast.satellite

import android.os.SystemClock

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
    private val timeline = Timeline()
    fun record(event: Event, recent: Boolean = true) = timeline.record(event, recent)
    fun summary(): String = timeline.summary()
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
