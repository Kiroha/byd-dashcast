package com.byd.dashcast.satellite

import android.app.Application
import com.byd.dashcast.hud.HudController
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Provider
import java.security.Security
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.X509TrustManager

/** Exercises the production listener and callbacks over real TLS, not synthetic onStart/onOpen calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SatelliteWebSocketTransportTest {
    private lateinit var context: Application
    private var removedProviders = emptyList<Pair<Int, Provider>>()
    private var server: SatelliteWebSocketServer? = null
    private var client: WebSocketClient? = null

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(SatellitePrefs.FILE, 0).edit().clear().commit()
        NavigationInputRouter.enableReceiver(context, true)
        NavigationInputRouter.selectRemote(context, true)
        // Android's instrumented Conscrypt cannot run a Java 21 server engine. The public test
        // identity and JDK provider exercise transport; AndroidKeyStore still needs device testing.
        removedProviders = Security.getProviders().mapIndexedNotNull { index, provider ->
            if (provider.javaClass.name.contains("conscrypt", ignoreCase = true)) index to provider else null
        }
        removedProviders.forEach { (_, provider) -> Security.removeProvider(provider.name) }
        // Java-WebSocket's optional SSLEngine assertions assume a FINISHED handshake state
        // that SunJSSE may expose as NOT_HANDSHAKING. Android does not enable these assertions.
        org.java_websocket.SSLSocketChannel2::class.java.classLoader!!
            .setClassAssertionStatus("org.java_websocket.SSLSocketChannel2", false)
    }

    @After fun tearDown() {
        client?.closeBlocking()
        server?.beginShutdown()
        server?.stop(1_000)
        SatellitePairingSession.close()
        HudController.closeNavigation(context)
        removedProviders.forEach { (index, provider) -> Security.insertProviderAt(provider, index + 1) }
    }

    @Test fun `real receiver binds TLS authenticates hello and accepts guidance`() {
        val listening = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        val welcomed = CountDownLatch(1)
        val navigated = CountDownLatch(1)
        val failure = AtomicReference<Exception?>()
        val received = AtomicReference<SatelliteProtocol.Navigation?>()
        val status = SatelliteStatusTracker()
        val owner = status.begin()
        val token = SatellitePrefs.token(context)
        val receiver = SatelliteWebSocketServer(context, serverContext(), token,
            object : SatelliteWebSocketServer.Events {
                override fun listening() { status.listening(owner); listening.countDown() }
                override fun connected(session: String) { status.connected(owner, session); accepted.countDown() }
                override fun navigation(session: String, frame: SatelliteProtocol.Navigation, receivedAtMs: Long) {
                    received.set(frame); navigated.countDown()
                }
                override fun expired(session: String) = Unit
                override fun disconnected(session: String) { status.disconnected(owner, session) }
                override fun failed() { status.failed(owner) }
            })
        server = receiver
        receiver.isDaemon = true
        receiver.start()
        assertTrue("Receiver never reported a bound listener", listening.await(5, TimeUnit.SECONDS))
        assertEquals(SatelliteStatusTracker.Connection.WAITING, status.snapshot(true, true, 0).connection)
        val sender = object : WebSocketClient(URI("wss://127.0.0.1:${receiver.port}${SatelliteProtocol.PATH}")) {
            override fun onSetSSLParameters(parameters: SSLParameters) {
                // The pairing contract pins the certificate; it does not assert a public DNS name.
                parameters.endpointIdentificationAlgorithm = null
                parameters.protocols = arrayOf("TLSv1.2")
            }
            override fun onOpen(handshake: ServerHandshake) {
                send(JSONObject().put("type", "hello").put("version", 1).put("token", token).toString())
            }
            override fun onMessage(message: String) {
                if (JSONObject(message).optString("type") == "welcome") {
                    welcomed.countDown()
                    send(JSONObject().put("type", "navigation.update").put("seq", 1).put("ageMs", 0)
                        .put("maneuver", "right").put("distanceMeters", 150).toString())
                }
            }
            override fun onClose(code: Int, reason: String, remote: Boolean) = Unit
            override fun onError(error: Exception) { failure.set(error) }
        }
        client = sender
        sender.setSocketFactory(pinnedContext().socketFactory)
        val connected = sender.connectBlocking(5, TimeUnit.SECONDS)
        assertTrue("TLS/WebSocket connection failed: ${failure.get()}", connected)
        assertTrue("Receiver did not authenticate hello", accepted.await(5, TimeUnit.SECONDS))
        assertTrue("Receiver did not send welcome", welcomed.await(5, TimeUnit.SECONDS))
        assertTrue("Receiver did not accept guidance", navigated.await(5, TimeUnit.SECONDS))
        assertEquals(1L, received.get()?.sequence)
        assertEquals(SatelliteStatusTracker.Connection.CONNECTED, status.snapshot(true, true, 0).connection)
        assertNull(failure.get())
    }

    private fun serverContext(): SSLContext {
        val store = KeyStore.getInstance("PKCS12").apply {
            this@SatelliteWebSocketTransportTest.javaClass.getResourceAsStream("/tls/test-only-server.p12")!!
                .use { load(it, "test-only".toCharArray()) }
        }
        val manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, "test-only".toCharArray())
        }
        return SSLContext.getInstance("TLSv1.2", "SunJSSE").apply { init(manager.keyManagers, null, null) }
    }

    private fun pinnedContext(): SSLContext {
        val certificate = javaClass.getResourceAsStream("/tls/receiver.pem")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val expected = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        val manager = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("No client certificates")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val actual = chain?.firstOrNull()?.encoded?.let { MessageDigest.getInstance("SHA-256").digest(it) }
                if (actual == null || !MessageDigest.isEqual(expected, actual)) throw CertificateException("Identity mismatch")
            }
        }
        return SSLContext.getInstance("TLSv1.2", "SunJSSE").apply { init(null, arrayOf(manager), null) }
    }
}
