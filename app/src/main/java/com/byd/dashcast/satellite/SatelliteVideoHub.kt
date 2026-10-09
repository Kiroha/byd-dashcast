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
        send?.invoke(JSONObject().put("type", "video.ready").toString())
    }

    @Synchronized
    fun detach(owner: Viewer) {
        if (viewer !== owner) return
        viewer = null
        send?.invoke(JSONObject().put("type", "video.closed").toString())
    }

    @Synchronized
    fun connect(id: String, sender: (String) -> Unit) {
        viewer?.reset()
        session = id
        send = sender
        if (viewer != null) sender(JSONObject().put("type", "video.ready").toString())
    }

    @Synchronized
    fun disconnect(id: String) {
        if (session != id) return
        session = null
        send = null
        viewer?.reset()
    }

    @Synchronized
    fun signal(id: String, message: JSONObject): Boolean {
        if (session != id) return false
        val target = viewer ?: return false
        target.signal(id, message)
        return true
    }

    @Synchronized
    fun reply(owner: Viewer, id: String, message: String) {
        if (viewer === owner && session == id) send?.invoke(message)
    }
}
