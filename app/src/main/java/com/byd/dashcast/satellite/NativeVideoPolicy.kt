package com.byd.dashcast.satellite

/** Pure bounds shared by native signalling and its real-render watchdog. No DNS or relay fallback. */
internal object NativeVideoPolicy {
    const val SDK = "150.7871.01"
    const val MAX_ICE = 128
    fun localIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.toIntOrNull() !in 0..255 || (it.length > 1 && it[0] == '0') }) return false
        val a = parts.map(String::toInt)
        return a[0] == 10 || (a[0] == 172 && a[1] in 16..31) || (a[0] == 192 && a[1] == 168) ||
            (a[0] == 169 && a[1] == 254)
    }
    fun candidate(value: String): String? {
        if (value.length !in 1..2048) return null
        val f = value.trim().split(Regex("\\s+"))
        if (f.size < 8 || !f[0].startsWith("candidate:") || f[1] != "1" || !f[2].equals("udp", true) ||
            f[5].toIntOrNull() !in 1..65535 || f[6] != "typ" || f[7] != "host" ||
            f.drop(8).any { it == "raddr" || it == "rport" } || !localIpv4(f[4])) return null
        return f[4]
    }
    fun sdp(value: String, allowed: Set<String>): String {
        require(value.length in 1..60_000)
        val lines = value.split(Regex("\\r?\\n"))
        require(lines.count { it.startsWith("m=") } == 1 && lines.any { it.startsWith("m=video ") })
        return lines.mapNotNull { line -> when {
            line.startsWith("a=candidate:") -> line.takeIf { candidate(it.removePrefix("a=")) in allowed }
            line.startsWith("c=") -> { require(line.startsWith("c=IN IP4 ") || line.startsWith("c=IN IP6 ")); "c=IN IP4 0.0.0.0" }
            else -> line
        } }.joinToString("\r\n")
    }
}

/** Only a successful EGL swap refreshes the image deadline. Stats and decoded inputs cannot. */
internal class NativeImageWatchdog(private val started: Long) {
    private var answered: Long? = null
    private var rendered: Long? = null
    @Synchronized fun answered(now: Long) { if (answered == null) answered = now }
    @Synchronized fun rendered(now: Long) { rendered = now }
    @Synchronized fun expired(now: Long): Boolean = rendered?.let { now - it >= 6_000 } ?: (
        now - started >= 30_000 || answered?.let { now - it >= 15_000 } == true)
}

/** One scheduled consumer and at most 32 retained signals. A new offer drops obsolete ICE/SDP. */
internal class NativeSignalInbox<T>(private val limit: Int = 32) {
    enum class Result { SCHEDULE, QUEUED, OVERFLOW }
    private val queue = ArrayDeque<T>()
    private var scheduled = false
    @Synchronized fun submit(value: T, replace: Boolean = false): Result {
        if (replace) queue.clear()
        if (queue.size >= limit) return Result.OVERFLOW
        queue.addLast(value)
        return if (scheduled) Result.QUEUED else { scheduled = true; Result.SCHEDULE }
    }
    @Synchronized fun take(): T? {
        if (queue.isEmpty()) { scheduled = false; return null }
        return queue.removeFirst()
    }
    @Synchronized fun clear() { queue.clear() }
    @Synchronized fun size() = queue.size
}

/** The SDK owns and automatically disposes callback transceivers. Queue no SDK object. */
internal class NativeTrackNotification(private val alive: () -> Boolean,
    private val enqueue: (() -> Unit) -> Unit, private val bindCurrentReceiver: () -> Unit) {
    private val submitted = java.util.concurrent.atomic.AtomicBoolean(false)
    fun received() {
        if (!submitted.compareAndSet(false, true)) return
        enqueue { if (alive()) bindCurrentReceiver() }
    }
}
