package com.byd.dashcast.clusterlive

/** Only the researched selection query is sent. This evidence never admits a theme load. */
internal object ClusterThemeReadOnlyQuery {
    interface Transport : AutoCloseable {
        fun verifyIdentity()
        fun alive(): Boolean
        fun register(receiver: (Int, ByteArray) -> Unit): Int
        fun sendQuery(bytes: ByteArray): Int
    }

    data class Receipt(val state: String, val phase: String, val detail: String,
        val registration: Int? = null, val native: Int? = null,
        val response: ClusterThemeProtocol.Response? = null, val rejected: Int = 0, val malformed: Int = 0)

    fun execute(transport: Transport, deadline: Long, clock: () -> Long, pause: () -> Unit): Receipt {
        val lock = Any()
        var accepting = false
        var finished = false
        var rejected = 0
        var malformed = 0
        var received = 0
        var response: ClusterThemeProtocol.Response? = null
        var registration: Int? = null
        var native: Int? = null
        var phase = "java-identity"
        fun current() {
            check(clock() < deadline) { "Read-only query deadline exceeded" }
            check(transport.alive()) { "Pinned AutoContainer Binder died; no reconnect/replay" }
        }
        try {
            transport.verifyIdentity()
            current()
            phase = "callback-registration"
            registration = transport.register { uid, bytes ->
                synchronized(lock) {
                    if (!finished && accepting && clock() < deadline && received < 32) {
                        received++
                        if (uid != 1000) rejected++
                        else {
                            val decoded = runCatching { ClusterThemeProtocol.decode(bytes) }.getOrNull()
                            if (decoded == null) malformed++
                            else if (decoded.cmd == 1001 && decoded.sub == 4) {
                                if (decoded.ints[0] == 1 && decoded.ints[3] in 0..1 &&
                                    decoded.strings[0].isNotEmpty() && decoded.strings[2].isNotEmpty()) response = decoded
                                else malformed++
                            }
                        }
                    }
                }
            }
            check(registration == 0) { "OEM callback registration refused: $registration" }
            current()
            phase = "selection-query"
            synchronized(lock) { accepting = true }
            // No filename, carrier, restore command or caller-supplied payload enters this path.
            native = transport.sendQuery(ClusterThemeProtocol.query())
            check(native == 0) { "OEM query native result: $native" }
            phase = "selection-response"
            while (clock() < deadline && synchronized(lock) { response == null }) {
                current()
                pause()
            }
            current()
            val selected = synchronized(lock) { response } ?: error("No supported selection response")
            phase = "java-identity-recheck"
            transport.verifyIdentity()
            current()
            return Receipt("RECEIVED", "complete", "OEM selection received; native renderer identity and physical pixels remain UNKNOWN.",
                registration, native, selected, rejected, malformed)
        } catch (error: Exception) {
            return Receipt("UNAVAILABLE", phase, "${error.javaClass.simpleName}: ${error.message}".take(768),
                registration, native, rejected = rejected, malformed = malformed)
        } finally {
            synchronized(lock) { finished = true; accepting = false }
            transport.close()
        }
    }
}
