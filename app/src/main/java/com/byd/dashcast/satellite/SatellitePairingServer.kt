package com.byd.dashcast.satellite

import java.io.Closeable
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ScheduledFuture

/** Temporary local bootstrap. A PAKE authenticates the short code before releasing an encrypted profile. */
internal class SatellitePairingServer(
    private val profile: String,
    private val code: String,
    private val port: Int = SatellitePairingCode.PORT,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onClosed: () -> Unit = {},
    lifetimeMs: Long = SatellitePairingCode.TTL_MS,
) : Closeable {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val expiresAt = clock() + lifetimeMs.coerceIn(0, SatellitePairingCode.TTL_MS)
    private var listener: ServerSocket? = null
    private val clients = mutableSetOf<Socket>()
    private var attempts = 0
    private var exchanges = 0
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        SynchronousQueue(), { runnable ->
            Thread(runnable, "satellite-pair-request").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy())
    private val expiry = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "satellite-pair-expiry").apply { isDaemon = true }
    }

    internal val localPort: Int get() = synchronized(lock) { listener?.localPort ?: -1 }
    internal val remainingMs: Long get() = if (closed.get()) 0 else (expiresAt - clock()).coerceAtLeast(0)

    /** May run off the UI thread. close() before or during start() permanently cancels this instance. */
    fun start(): Boolean {
        check(started.compareAndSet(false, true))
        val socket = ServerSocket()
        synchronized(lock) {
            if (!isLive()) { socket.close(); close(); return false }
            listener = socket
        }
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            synchronized(lock) {
                if (!isLive()) { close(); return false }
                expiry.schedule({ close() }, remainingMs, TimeUnit.MILLISECONDS)
                Thread({ accept(socket) }, "satellite-pair-listen").apply { isDaemon = true }.start()
            }
            return true
        } catch (_: Exception) {
            close()
            return false
        }
    }

    private fun isLive(): Boolean = !closed.get() && clock() < expiresAt

    private fun accept(server: ServerSocket) {
        try {
            while (isLive()) {
                val client = server.accept()
                synchronized(lock) {
                    if (!isLive() || !SatelliteProtocol.isLocalAddress(client.inetAddress)) {
                        client.close()
                        return@synchronized
                    }
                    clients.add(client)
                    try { workers.execute { serve(client) } }
                    catch (_: Exception) { clients.remove(client); client.close() }
                }
            }
        } catch (_: Exception) {
            // Includes normal cancellation. No request, profile or code is logged.
        } finally { close() }
    }

    private fun serve(client: Socket) {
        var reserved = false
        var timeout: ScheduledFuture<*>? = null
        try {
            client.tcpNoDelay = true
            val deadline = clock() + REQUEST_TIMEOUT_MS
            timeout = expiry.schedule({ try { client.close() } catch (_: Exception) {} },
                minOf(REQUEST_TIMEOUT_MS, remainingMs), TimeUnit.MILLISECONDS)
            val input = client.getInputStream()
            val output = DataOutputStream(client.getOutputStream())
            fun readExact(size: Int): ByteArray {
                val bytes = ByteArray(size)
                var offset = 0
                while (offset < size) {
                    val remaining = minOf(deadline - clock(), remainingMs)
                    check(isLive() && remaining > 0)
                    client.soTimeout = remaining.coerceAtMost(REQUEST_TIMEOUT_MS).toInt()
                    val read = input.read(bytes, offset, size - offset)
                    if (read < 0) throw EOFException()
                    offset += read
                }
                return bytes
            }
            fun receive(): String {
                val size = ByteBuffer.wrap(readExact(4)).int
                require(size in 1..SatellitePairingCode.MAX_FRAME_BYTES)
                return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(readExact(size))).toString()
            }
            val first = receive()
            require(SatellitePairingExchange.isInitialMessage(first))
            synchronized(lock) {
                check(isLive() && attempts < MAX_ATTEMPTS)
                attempts++
                exchanges++
                reserved = true
            }
            var initial: String? = first
            SatellitePairingExchange.server(profile, code, send = { text ->
                check(isLive() && clock() < deadline)
                val bytes = text.toByteArray(Charsets.UTF_8)
                require(bytes.size in 1..SatellitePairingCode.MAX_FRAME_BYTES)
                output.writeInt(bytes.size)
                output.write(bytes)
                output.flush()
            }, receive = {
                initial?.also { initial = null } ?: receive()
            })
        } catch (_: Exception) {
            // Wrong codes, malformed frames and cancelled sockets all close without an oracle or payload log.
        } finally {
            timeout?.cancel(false)
            val exhausted = synchronized(lock) {
                clients.remove(client)
                if (reserved) exchanges--
                attempts >= MAX_ATTEMPTS && exchanges == 0
            }
            try { client.close() } catch (_: Exception) {}
            if (exhausted) close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            try { listener?.close() } catch (_: Exception) {}
            listener = null
            clients.forEach { try { it.close() } catch (_: Exception) {} }
            clients.clear()
        }
        workers.shutdownNow()
        expiry.shutdownNow()
        onClosed()
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}

/** RAM-only pairing window owned by the receiver service, independent of the settings screen. */
internal object SatellitePairingSession {
    private val lock = Any()
    private var generation = 0L
    private var active: Window? = null

    private class Window(val attempt: Long, val code: String,
        val expiresAt: Long = now() + SatellitePairingCode.TTL_MS) {
        var server: SatellitePairingServer? = null
        var profile: String? = null
        var addresses: List<String> = emptyList()
    }

    /** No generated toString: the code and profile must never appear in diagnostics. */
    class Snapshot(val attempt: Long, val code: String, val profile: String?,
        val addresses: List<String>, val remainingMs: Long, val ready: Boolean)

    private fun now() = System.nanoTime() / 1_000_000

    fun begin(): Long {
        val code = SatellitePairingCode.newCode()
        val (attempt, previous) = synchronized(lock) {
            val previous = active
            val attempt = ++generation
            active = Window(attempt, code)
            attempt to previous
        }
        previous?.server?.close()
        return attempt
    }

    fun snapshot(): Snapshot? {
        val (initial, server) = synchronized(lock) {
            val window = active ?: return null
            Snapshot(window.attempt, window.code, window.profile, window.addresses,
                window.expiresAt - now(), false) to window.server
        }
        val state = Snapshot(initial.attempt, initial.code, initial.profile, initial.addresses,
            minOf(initial.remainingMs, server?.remainingMs ?: Long.MAX_VALUE), (server?.localPort ?: -1) > 0)
        if (!isCurrent(state.attempt)) return null
        if (state.remainingMs <= 0) { close(state.attempt); return null }
        return state
    }

    fun isCurrent(attempt: Long): Boolean = synchronized(lock) { active?.attempt == attempt }

    fun attach(attempt: Long, server: SatellitePairingServer, profile: String? = null,
        addresses: List<String> = emptyList()): Boolean = synchronized(lock) {
        val window = active
        if (window == null || window.attempt != attempt || window.server != null || now() >= window.expiresAt) false
        else {
            window.server = server
            window.profile = profile
            window.addresses = addresses.toList()
            true
        }
    }

    fun close(attempt: Long? = null) {
        val previous = synchronized(lock) {
            if (attempt != null && active?.attempt != attempt) return
            generation++
            active.also { active = null }
        }
        previous?.server?.close()
    }
}
