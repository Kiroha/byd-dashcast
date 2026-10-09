package com.byd.dashcast.satellite

import android.content.Context
import android.os.SystemClock
import com.byd.dashcast.util.AppLogger
import org.java_websocket.WebSocket
import org.java_websocket.WebSocketImpl
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID
import javax.net.ssl.SSLContext

/** Small encrypted control server. WebRTC video never passes through this socket. */
class SatelliteWebSocketServer(
    private val context: Context,
    tls: SSLContext,
    private val token: String,
    private val events: Events,
) : WebSocketServer(InetSocketAddress(SatellitePrefs.PORT), 1,
    listOf(Draft_6455(emptyList(), emptyList(), SatelliteProtocol.MAX_MESSAGE_BYTES))) {

    interface Events {
        fun connected(session: String)
        fun navigation(session: String, frame: SatelliteProtocol.Navigation, receivedAtMs: Long)
        fun expired(session: String)
        fun disconnected(session: String)
        fun failed()
    }

    private data class Session(val connection: WebSocket, val id: String,
        val navigation: SatelliteNavigationGate = SatelliteNavigationGate(),
        var windowStart: Long = 0, var messages: Int = 0,
        var negotiation: String? = null, var candidates: Int = 0)
    private val pending = LinkedHashMap<WebSocket, Long>()
    private var active: Session? = null
    @Volatile private var shuttingDown = false

    init {
        setWebSocketFactory(DefaultSSLWebSocketServerFactory(tls))
        isTcpNoDelay = true
        isReuseAddr = true
        connectionLostTimeout = 5
    }

    @Synchronized
    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val address = conn.remoteSocketAddress?.address
        if (!configurationValid() || handshake.resourceDescriptor != SatelliteProtocol.PATH ||
            address == null || !SatelliteProtocol.isLocalAddress(address) ||
            pending.size >= 2 || active != null) {
            conn.close(1008, "unavailable")
            return
        }
        pending[conn] = SystemClock.elapsedRealtime()
    }

    @Synchronized
    override fun onMessage(conn: WebSocket, message: String) {
        if (!configurationValid()) { conn.close(1008, "disabled or revoked"); return }
        try {
            val json = SatelliteProtocol.decode(message)
            val type = json.getString("type")
            val session = active
            if (session == null || session.connection !== conn) {
                if (!pending.containsKey(conn) || type != "hello" || active != null ||
                    SystemClock.elapsedRealtime() - pending.getValue(conn) >= SatelliteProtocol.AUTH_TIMEOUT_MS ||
                    SatelliteProtocol.integer(json, "version", 1, 1) != SatelliteProtocol.VERSION.toLong() ||
                    json.get("token") !is String || !SatellitePrefs.tokenMatches(token, json.getString("token"))) {
                    conn.close(1008, "authentication failed")
                    return
                }
                pending.remove(conn)
                val created = Session(conn, UUID.randomUUID().toString())
                active = created
                SatellitePairingSession.close()
                events.connected(created.id)
                sendBounded(conn, JSONObject().put("type", "welcome").put("version", SatelliteProtocol.VERSION)
                    .put("session", created.id).put("navigationTimeoutMs", SatelliteProtocol.NAV_TIMEOUT_MS)
                    .put("remoteGuidance", SatellitePrefs.usesRemoteGuidance(context))
                    .put("videoTransport", "webrtc").toString())
                SatelliteVideoHub.connect(created.id) { sendBounded(conn, it) }
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (now - session.windowStart >= 1_000) { session.windowStart = now; session.messages = 0 }
            if (++session.messages > 100) { conn.close(1008, "rate limit"); return }
            when (type) {
                "ping" -> sendBounded(conn, JSONObject().put("type", "pong").toString())
                "navigation.update", "navigation.stop" -> {
                    if (!SatellitePrefs.usesRemoteGuidance(context)) { error(conn, "guidance_disabled"); return }
                    val frame = SatelliteProtocol.navigation(json)
                    if (!session.navigation.accept(frame, now)) { error(conn, "stale_sequence"); return }
                    events.navigation(session.id, frame, now)
                }
                "video.offer", "video.ice", "video.stop" -> {
                    val negotiation = json.get("negotiation")
                    require(negotiation is String && negotiation.matches(Regex("[A-Za-z0-9_-]{1,64}")))
                    when (type) {
                        "video.offer" -> {
                            val sdp = json.get("sdp")
                            require(sdp is String && sdp.length in 1..60_000)
                            val media = sdp.lineSequence().filter { it.startsWith("m=") }.toList()
                            require(media.size == 1 && media.single().startsWith("m=video "))
                            require(session.negotiation != negotiation)
                            session.negotiation = negotiation
                            session.candidates = 0
                        }
                        "video.ice" -> {
                            require(session.negotiation == negotiation && ++session.candidates <= 128)
                            val candidate = json.get("candidate")
                            require(candidate is String && candidate.length in 1..2_048)
                            require(json.get("sdpMid") is String && json.getString("sdpMid").length <= 64)
                            SatelliteProtocol.integer(json, "sdpMLineIndex", 0, 8)
                        }
                        else -> require(session.negotiation == negotiation)
                    }
                    if (!SatelliteVideoHub.signal(session.id, json)) error(conn, "viewer_unavailable")
                    if (type == "video.stop") session.negotiation = null
                }
                else -> error(conn, "unsupported_message")
            }
        } catch (_: Exception) {
            // Never log payloads: hello contains a credential and navigation can contain a road.
            if (active?.connection !== conn) conn.close(1008, "authentication failed")
            else error(conn, "invalid_message")
        }
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        conn.close(1003, "text messages only")
    }

    @Synchronized
    fun tick(nowMs: Long) {
        if (!configurationValid()) {
            pending.keys.toList().forEach { it.close(1008, "disabled or revoked") }
            active?.connection?.close(1008, "disabled or revoked")
            return
        }
        pending.toMap().forEach { (conn, opened) ->
            if (nowMs - opened >= SatelliteProtocol.AUTH_TIMEOUT_MS) conn.close(1008, "authentication timeout")
        }
        active?.let { if (it.navigation.expire(nowMs)) events.expired(it.id) }
    }

    @Synchronized
    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        pending.remove(conn)
        val session = active ?: return
        if (session.connection !== conn) return
        active = null
        SatelliteVideoHub.disconnect(session.id)
        events.disconnected(session.id)
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        AppLogger.w("Satellite", "control transport: ${ex.javaClass.simpleName}")
        // A late TLS error from the previous transport must not stop a replacement service.
        if (conn == null && !shuttingDown) events.failed()
    }

    override fun onStart() { AppLogger.i("Satellite", "encrypted receiver listening") }

    @Synchronized
    fun beginShutdown() {
        shuttingDown = true
        active?.let {
            active = null
            SatelliteVideoHub.disconnect(it.id)
            events.disconnected(it.id)
        }
    }

    private fun configurationValid(): Boolean = !shuttingDown && SatellitePrefs.isEnabled(context) &&
        SatellitePrefs.tokenMatches(token, SatellitePrefs.token(context))

    private fun error(conn: WebSocket, code: String) =
        sendBounded(conn, JSONObject().put("type", "error").put("code", code).toString())

    private fun sendBounded(conn: WebSocket, text: String) {
        if (!conn.isOpen) return
        // A slow sender must not turn signalling into an unlimited outbound buffer.
        if (conn is WebSocketImpl && conn.outQueue.size >= 32) {
            conn.close(1008, "slow consumer")
            return
        }
        try { conn.send(text) } catch (_: Exception) { conn.close() }
    }
}
