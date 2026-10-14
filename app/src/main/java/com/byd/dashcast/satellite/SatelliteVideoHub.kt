package com.byd.dashcast.satellite

import org.json.JSONObject

/** One live viewer and one authenticated sender. No media is retained by the control service. */
object SatelliteVideoHub {
    interface Viewer {
        fun signal(session: String, message: JSONObject)
        fun reset()
    }
    private var viewer: Viewer? = null
    private var session: String? = null
    private var send: ((String) -> Unit)? = null

    @Synchronized
    fun attach(owner: Viewer) {
        viewer?.reset()
        viewer = owner
        SatelliteVideoDiagnostics.record(SatelliteVideoDiagnostics.Event.VIEWER_READY)
        send?.invoke(JSONObject().put("type", "video.ready").toString())
    }

    @Synchronized
    fun detach(owner: Viewer) {
        if (viewer !== owner) return
        viewer = null
        SatelliteVideoDiagnostics.record(SatelliteVideoDiagnostics.Event.VIEWER_CLOSED)
        send?.invoke(JSONObject().put("type", "video.closed").toString())
    }

    @Synchronized
    fun connect(id: String, sender: (String) -> Unit) {
        viewer?.reset()
        session = id
        send = sender
        SatelliteVideoDiagnostics.record(SatelliteVideoDiagnostics.Event.CONTROL_CONNECTED)
        if (viewer != null) sender(JSONObject().put("type", "video.ready").toString())
    }

    @Synchronized
    fun disconnect(id: String) {
        if (session != id) return
        session = null
        send = null
        SatelliteVideoDiagnostics.record(SatelliteVideoDiagnostics.Event.CONTROL_CLOSED)
        viewer?.reset()
    }

    @Synchronized
    fun signal(id: String, message: JSONObject, unavailable: () -> Unit = {}): Boolean {
        if (session != id) return false
        val target = viewer ?: run {
            // Queue rejection before attach can queue video.ready. Do not wait for network I/O.
            SatelliteVideoDiagnostics.record(SatelliteVideoDiagnostics.Event.VIEWER_UNAVAILABLE)
            unavailable()
            return false
        }
        when (message.optString("type")) {
            "video.offer" -> SatelliteVideoDiagnostics.record(SatelliteVideoDiagnostics.Event.OFFER_RECEIVED)
            "video.ice" -> SatelliteVideoDiagnostics.record(SatelliteVideoDiagnostics.Event.ICE_RECEIVED, recent = false)
            "video.stop" -> SatelliteVideoDiagnostics.record(SatelliteVideoDiagnostics.Event.SENDER_STOPPED)
        }
        target.signal(id, message)
        return true
    }

    @Synchronized
    fun reply(owner: Viewer, id: String, message: String) {
        if (viewer !== owner || session != id) return
        val json = runCatching { JSONObject(message) }.getOrNull()
        when (json?.optString("type")) {
            "video.answer" -> SatelliteVideoDiagnostics.record(SatelliteVideoDiagnostics.Event.ANSWER_QUEUED)
            "video.ice" -> SatelliteVideoDiagnostics.record(SatelliteVideoDiagnostics.Event.ICE_QUEUED, recent = false)
            "video.error" -> SatelliteVideoDiagnostics.failure(json.optString("code"))
        }
        send?.invoke(message)
    }
}
