package com.byd.dashcast.satellite

import android.app.Application
import com.byd.dashcast.hud.HudController
import org.java_websocket.WebSocket
import org.java_websocket.handshake.HandshakeImpl1Client
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import javax.net.ssl.SSLContext

/** Real server callbacks with recording sockets; these tests do not claim a device TLS handshake. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SatelliteWebSocketServerTest {
    private lateinit var context: Application
    private lateinit var server: SatelliteWebSocketServer
    private lateinit var token: String
    private val connected = mutableListOf<String>()
    private val frames = mutableListOf<SatelliteProtocol.Navigation>()
    private val disconnected = mutableListOf<String>()
    private var failures = 0

    private class Socket {
        val messages = mutableListOf<String>()
        var closed = false
        val socket = Proxy.newProxyInstance(WebSocket::class.java.classLoader, arrayOf(WebSocket::class.java)) { proxy, method, args ->
            when (method.name) {
                "getRemoteSocketAddress" -> InetSocketAddress("127.0.0.1", 12345)
                "isOpen" -> !closed
                "send" -> { messages.add(args!![0] as String); null }
                "close", "closeConnection" -> { closed = true; null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "toString" -> "recording socket"
                else -> null
            }
        } as WebSocket
    }

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(SatellitePrefs.FILE, 0).edit().clear().commit()
        NavigationInputRouter.enableReceiver(context, true)
        NavigationInputRouter.selectRemote(context, true)
        token = SatellitePrefs.token(context)
        val tls = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        server = SatelliteWebSocketServer(context, tls, token, object : SatelliteWebSocketServer.Events {
            override fun connected(session: String) { connected.add(session) }
            override fun navigation(session: String, frame: SatelliteProtocol.Navigation, receivedAtMs: Long) { frames.add(frame) }
            override fun expired(session: String) {}
            override fun disconnected(session: String) { disconnected.add(session) }
            override fun failed() { failures++ }
        })
    }

    @After fun tearDown() { server.beginShutdown(); HudController.closeNavigation(context) }

    private fun open(socket: Socket, path: String = SatelliteProtocol.PATH) {
        server.onOpen(socket.socket, HandshakeImpl1Client().apply { resourceDescriptor = path })
    }
    private fun hello(socket: Socket, version: Int = 1, suppliedToken: String = token) =
        server.onMessage(socket.socket, JSONObject().put("type", "hello").put("version", version).put("token", suppliedToken).toString())
    private fun update(socket: Socket, seq: Any = 1) = server.onMessage(socket.socket,
        JSONObject().put("type", "navigation.update").put("seq", seq).put("ageMs", 0)
            .put("maneuver", "right").put("distanceMeters", 200).toString())

    @Test fun `unauthenticated guidance never reaches the output boundary`() {
        val socket = Socket(); open(socket); update(socket)
        assertTrue(socket.closed); assertTrue(connected.isEmpty()); assertTrue(frames.isEmpty())
    }

    @Test fun `wrong pairing tokens versions and paths cannot establish a session`() {
        val wrongToken = Socket(); open(wrongToken); hello(wrongToken, suppliedToken = "invalid")
        val wrongVersion = Socket(); open(wrongVersion); hello(wrongVersion, version = 2)
        val wrongPath = Socket(); open(wrongPath, "/other"); hello(wrongPath)
        assertTrue(wrongToken.closed); assertTrue(wrongVersion.closed); assertTrue(wrongPath.closed)
        assertTrue(connected.isEmpty()); assertTrue(frames.isEmpty())
    }

    @Test fun `authenticated guidance is validated and replayed sequences are refused`() {
        val socket = Socket(); open(socket); hello(socket)
        assertEquals("welcome", JSONObject(socket.messages.first()).getString("type"))
        update(socket, 1.5); update(socket, 1); update(socket, 1); update(socket, 2)
        assertEquals(listOf(1L, 2L), frames.map { it.sequence })
    }

    @Test fun `a second sender cannot take over or clear the authenticated route`() {
        val first = Socket(); open(first); hello(first)
        val second = Socket(); open(second); hello(second)
        server.onClose(second.socket, 1008, "", true)
        update(first)
        assertTrue(second.closed); assertFalse(first.closed)
        assertEquals(1, connected.size); assertTrue(disconnected.isEmpty()); assertEquals(1, frames.size)
    }

    @Test fun `revocation invalidates existing sessions before another navigation write`() {
        val socket = Socket(); open(socket); hello(socket)
        SatellitePrefs.rotateToken(context)
        update(socket)
        assertTrue(socket.closed); assertTrue(frames.isEmpty())
    }

    @Test fun `guidance selection remains separate from control and video connectivity`() {
        val socket = Socket(); open(socket); hello(socket)
        NavigationInputRouter.selectRemote(context, false)
        update(socket)
        assertTrue(frames.isEmpty()); assertFalse(socket.closed)
        assertEquals("guidance_disabled", JSONObject(socket.messages.last()).getString("code"))
    }

    @Test fun `shutdown fences out late messages from the old transport`() {
        val socket = Socket(); open(socket); hello(socket)
        server.beginShutdown(); update(socket)
        assertTrue(socket.closed); assertTrue(frames.isEmpty()); assertEquals(1, disconnected.size)
    }

    @Test fun `a late failure from a stopped transport cannot terminate its replacement`() {
        server.beginShutdown()
        server.onError(null, IllegalStateException("old transport"))
        assertEquals(0, failures)
    }
}
