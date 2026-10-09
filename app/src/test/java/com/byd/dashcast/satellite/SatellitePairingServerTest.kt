package com.byd.dashcast.satellite

import android.app.Application
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Real loopback sockets exercise framed PAKE transfer and cancellation, without a vehicle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SatellitePairingServerTest {
    private val servers = mutableListOf<SatellitePairingServer>()
    private val profile = "{\"token\":\"private-installation-token\",\"certificateSha256\":\"private-pin\"}"
    private val code = "038519"

    @Before fun setUp() {
        SatellitePairingSession.close()
        RuntimeEnvironment.getApplication().getSharedPreferences(SatellitePrefs.FILE, 0).edit().clear().commit()
    }

    @After fun tearDown() {
        SatellitePairingSession.close()
        servers.forEach { it.close() }
    }

    private fun server(clock: () -> Long = { System.nanoTime() / 1_000_000 },
        onClosed: () -> Unit = {}): SatellitePairingServer =
        SatellitePairingServer(profile, code, 0, clock, onClosed).also { servers.add(it) }

    private fun send(socket: Socket, frame: String) {
        val bytes = frame.toByteArray(Charsets.UTF_8)
        DataOutputStream(socket.getOutputStream()).apply { writeInt(bytes.size); write(bytes); flush() }
    }

    private fun receive(socket: Socket): String {
        val input = DataInputStream(socket.getInputStream())
        val length = input.readInt()
        require(length in 1..SatellitePairingCode.MAX_FRAME_BYTES)
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }

    private fun initialMessage(): String {
        var first: String? = null
        try {
            SatellitePairingExchange.client(code, send = { first = it; throw CaptureComplete() },
                receive = { error("client must send first") })
        } catch (_: CaptureComplete) {}
        return requireNotNull(first)
    }

    private class CaptureComplete : RuntimeException()

    @Test fun `real socket exchange releases profile only after mutual code confirmation`() {
        val server = server()
        assertTrue(server.start())
        val wire = mutableListOf<String>()
        val result = Socket("127.0.0.1", server.localPort).use { socket ->
            socket.soTimeout = 5_000
            SatellitePairingExchange.client(code, send = { wire.add(it); send(socket, it) },
                receive = { receive(socket).also(wire::add) })
        }
        assertEquals(profile, result)
        assertTrue(wire.isNotEmpty())
        assertFalse(wire.any { it.contains("private-installation-token") || it.contains("private-pin") })
        assertFalse(wire.any { org.json.JSONObject(it).has("code") || org.json.JSONObject(it).has("password") })
    }

    @Test fun `incorrect code cannot retrieve the profile`() {
        val server = server()
        assertTrue(server.start())
        val received = mutableListOf<String>()
        Socket("127.0.0.1", server.localPort).use { socket ->
            socket.soTimeout = 5_000
            try {
                SatellitePairingExchange.client("999999", send = { send(socket, it) },
                    receive = { receive(socket).also(received::add) })
                fail("wrong code returned a profile")
            } catch (_: Exception) {}
        }
        assertFalse(received.any { org.json.JSONObject(it).optString("type") == "profile" })
        assertFalse(received.any { it.contains("private-installation-token") })
    }

    @Test fun `oversized negative malformed UTF8 and wrong initial frames are refused`() {
        val server = server()
        assertTrue(server.start())
        val frames = listOf(
            java.nio.ByteBuffer.allocate(4).putInt(-1).array(),
            java.nio.ByteBuffer.allocate(4).putInt(SatellitePairingCode.MAX_FRAME_BYTES + 1).array(),
            byteArrayOf(0, 0, 0, 1, 0xff.toByte()),
            byteArrayOf(0, 0, 0, 2, '{'.code.toByte(), '}'.code.toByte()),
        )
        for (frame in frames) {
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().write(frame)
                try { assertEquals(-1, socket.getInputStream().read()) } catch (_: SocketException) {}
            }
        }
    }

    @Test fun `port scans do not consume attempts and five real attempts exhaust the window`() {
        val stopped = CountDownLatch(1)
        val server = server(onClosed = { stopped.countDown() })
        assertTrue(server.start())
        val port = server.localPort
        repeat(8) {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 2_000
                send(socket, "{}")
                assertEquals(-1, socket.getInputStream().read())
            }
        }
        val first = initialMessage()
        repeat(5) {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                send(socket, first)
                assertTrue(receive(socket).isNotEmpty())
                // Abandon after the receiver accepts a shape-valid first round.
            }
        }
        assertTrue("attempt budget did not close pairing", stopped.await(3, TimeUnit.SECONDS))
        assertEquals(-1, server.localPort)
    }

    @Test fun `close cancels a partial request and listener without waiting for its read timeout`() {
        val closed = AtomicInteger()
        val server = server(onClosed = { closed.incrementAndGet() })
        assertTrue(server.start())
        val port = server.localPort
        Socket("127.0.0.1", port).use { client ->
            client.soTimeout = 2_000
            client.getOutputStream().write(byteArrayOf(0, 0))
            server.close()
            try { assertEquals(-1, client.getInputStream().read()) } catch (_: SocketException) {}
        }
        server.close()
        assertEquals(1, closed.get())
        try { Socket("127.0.0.1", port).use { fail("closed listener still accepted a connection") } }
        catch (_: java.io.IOException) {}
    }

    @Test fun `closing before startup never opens a listener`() {
        val closed = AtomicInteger()
        val server = server(onClosed = { closed.incrementAndGet() })
        server.close()
        assertFalse(server.start())
        assertEquals(-1, server.localPort)
        assertEquals(1, closed.get())
    }

    @Test fun `deadline timer closes an open partial request without a visible screen`() {
        val stopped = CountDownLatch(1)
        val server = SatellitePairingServer(profile, code, 0, lifetimeMs = 250,
            onClosed = { stopped.countDown() }).also(servers::add)
        assertTrue(server.start())
        Socket("127.0.0.1", server.localPort).use { socket ->
            socket.soTimeout = 2_000
            socket.getOutputStream().write(byteArrayOf(0, 0))
            try { assertEquals(-1, socket.getInputStream().read()) } catch (_: SocketException) {}
        }
        assertTrue(stopped.await(1, TimeUnit.SECONDS))
        assertEquals(-1, server.localPort)
    }

    @Test fun `an expired code cannot return ciphertext even when listener has not ticked`() {
        val clock = AtomicLong(1_000)
        val server = server(clock::get)
        assertTrue(server.start())
        val port = server.localPort
        clock.addAndGet(SatellitePairingCode.TTL_MS)
        assertEquals(0L, server.remainingMs)
        try {
            Socket("127.0.0.1", port).use { client ->
                client.soTimeout = 2_000
                client.getOutputStream().write(byteArrayOf(0, 0, 0, 2, 123, 125))
                assertEquals(-1, client.getInputStream().read())
            }
        } catch (_: SocketException) {}
    }

    @Test fun `two partial requests do not create a queue for a third request`() {
        val server = server()
        assertTrue(server.start())
        val first = Socket("127.0.0.1", server.localPort)
        val second = Socket("127.0.0.1", server.localPort)
        try {
            for (client in listOf(first, second)) {
                client.soTimeout = 200
                client.getOutputStream().write(byteArrayOf(0, 0))
                try { client.getInputStream().read(); fail("partial request unexpectedly completed") }
                catch (_: SocketTimeoutException) {}
            }
            Socket("127.0.0.1", server.localPort).use { third ->
                third.soTimeout = 2_000
                try { assertEquals(-1, third.getInputStream().read()) } catch (_: SocketException) {}
            }
        } finally { first.close(); second.close() }
    }

    @Test fun `cancelled background preparation cannot attach after a new dialog begins`() {
        val first = SatellitePairingSession.begin()
        val server = server()
        val replacement = SatellitePairingSession.begin()
        assertFalse(SatellitePairingSession.attach(first, server))
        server.close()
        assertFalse(server.start())
        SatellitePairingSession.close(first)
        assertTrue(SatellitePairingSession.isCurrent(replacement))
    }

    @Test fun `token rotation and receiver disable close the current pairing listener`() {
        val context = RuntimeEnvironment.getApplication()
        for (invalidate in listOf<() -> Unit>(
            { SatellitePrefs.rotateToken(context) }, { SatellitePrefs.setEnabled(context, false) })) {
            val attempt = SatellitePairingSession.begin()
            val server = server()
            assertTrue(SatellitePairingSession.attach(attempt, server))
            assertTrue(server.start())
            invalidate()
            assertFalse(SatellitePairingSession.isCurrent(attempt))
            assertEquals(-1, server.localPort)
        }
    }

    @Test fun `creating the initial installation token does not cancel pairing preparation`() {
        val attempt = SatellitePairingSession.begin()
        assertTrue(SatellitePrefs.token(RuntimeEnvironment.getApplication()).isNotEmpty())
        assertTrue(SatellitePairingSession.isCurrent(attempt))
    }
    @Test fun `switching screens restores the same RAM-only code without extending its deadline`() {
        SatellitePrefs.setEnabled(RuntimeEnvironment.getApplication(), true)
        val attempt = SatellitePairingSession.begin()
        val original = requireNotNull(SatellitePairingSession.snapshot())
        val server = server()
        assertTrue(SatellitePairingSession.attach(attempt, server, profile, listOf("192.168.49.1")))
        assertTrue(server.start())
        val controller = Robolectric.buildActivity(SatelliteSettingsActivity::class.java).create().start().resume()
        try {
            controller.pause().stop()
            val background = requireNotNull(SatellitePairingSession.snapshot())
            assertEquals(original.code, background.code)
            assertEquals(attempt, background.attempt)
            assertTrue(background.remainingMs <= original.remainingMs)
            assertTrue(server.localPort > 0)
            controller.start().resume()
            val restored = requireNotNull(SatellitePairingSession.snapshot())
            assertEquals(original.code, restored.code)
            assertEquals(attempt, restored.attempt)
            assertTrue(restored.remainingMs <= background.remainingMs)
            controller.pause().stop()
        } finally { controller.destroy() }
    }

    @Test fun `foreground receiver destruction cancels background pairing and does not recreate it`() {
        val controller = Robolectric.buildService(SatelliteReceiverService::class.java).create()
        val attempt = SatellitePairingSession.begin()
        val server = server()
        assertTrue(SatellitePairingSession.attach(attempt, server))
        assertTrue(server.start())
        controller.destroy()
        assertNull(SatellitePairingSession.snapshot())
        assertEquals(-1, server.localPort)
        val replacement = Robolectric.buildService(SatelliteReceiverService::class.java).create()
        assertNull(SatellitePairingSession.snapshot())
        replacement.destroy()
    }

    @Test fun `dismissing the code while the settings screen is visible explicitly cancels pairing`() {
        SatellitePrefs.setEnabled(RuntimeEnvironment.getApplication(), true)
        val attempt = SatellitePairingSession.begin()
        val server = server()
        assertTrue(SatellitePairingSession.attach(attempt, server))
        assertTrue(server.start())
        val controller = Robolectric.buildActivity(SatelliteSettingsActivity::class.java).create().start().resume()
        try {
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            assertTrue(dialog.isShowing)
            dialog.cancel()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNull(SatellitePairingSession.snapshot())
            assertEquals(-1, server.localPort)
        } finally { controller.pause().stop().destroy() }
    }

}
