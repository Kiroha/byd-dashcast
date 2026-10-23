package com.byd.dashcast.cluster

/** Artifact-derived nominal ordering. Broadcast completion is delivery, never a rendered-state ACK. */
internal class ProjectionAdasPlan(private val io: Io) {
    interface Io {
        fun later(delay: Long, action: () -> Unit)
        fun now(): Long
        fun valid(): Boolean
        fun journal(done: (Boolean) -> Unit)
        fun initialize(valid: () -> Boolean, done: (Boolean) -> Unit)
        fun command(code: Int, valid: () -> Boolean, done: (Boolean) -> Unit)
        fun frames(frame: String, count: Int, valid: () -> Boolean, done: (Boolean) -> Unit)
    }
    @Volatile private var generation = 0L
    private var batch = 0L
    private var active = false
    private var recoveryReady = false
    private var busy = false
    private var confirmationPending = false
    private var pendingConfirmation = false
    private var pendingTransition = false
    private var powerOk = false
    private var transition = 0L
    private var lastInit = Long.MIN_VALUE
    private var firstDelivery: ((Boolean) -> Unit)? = null

    @Synchronized fun cancel() {
        generation++; batch++; transition++
        active = false; busy = false; recoveryReady = false
        confirmationPending = false; pendingConfirmation = false; pendingTransition = false
        firstDelivery = null
    }
    @Synchronized fun start(initialRawPower: Int? = null, delivered: ((Boolean) -> Unit)? = null) {
        cancel()
        firstDelivery = delivered
        active = true
        confirmationPending = true
        powerOk = initialRawPower == 3
        lastInit = Long.MIN_VALUE
        val g = generation
        io.journal { saved ->
            if (!current(g)) return@journal
            if (saved) { recoveryReady = true; pass(g, true) } else delivery(false)
        }
    }
    @Synchronized private fun current(g: Long) = active && generation == g && io.valid()
    @Synchronized private fun current(g: Long, b: Long) = current(g) && batch == b
    private fun delivery(ok: Boolean) {
        val callback = firstDelivery ?: return
        firstDelivery = null
        callback(ok)
    }

    private fun pass(g: Long, confirm: Boolean) {
        if (!current(g)) return
        val b = ++batch
        busy = true
        val valid = { current(g, b) }
        fun finish(ok: Boolean) {
            if (!valid()) return
            busy = false
            if (!ok) { confirmationPending = false; pendingConfirmation = false; pendingTransition = false; return }
            if (confirm) io.later(5_000) {
                if (confirmationPending && current(g)) { confirmationPending = false; requestPass(g, false) }
            }
            drain(g)
        }
        io.journal { saved ->
            if (!valid()) return@journal
            if (!saved) { delivery(false); finish(false); return@journal }
            io.command(13, valid) { commandOk ->
                if (!valid()) return@command
                if (!commandOk) { delivery(false); finish(false); return@command }
                io.initialize(valid) { initialized ->
                    if (!valid()) return@initialize
                    if (!initialized) { delivery(false); finish(false); return@initialize }
                    lastInit = io.now()
                    io.later(1_000) {
                        if (valid()) io.frames(HIDE, 3, valid) { ok ->
                            if (valid()) {
                                delivery(ok)
                                if (ok) phase(g, b, 0, 0, ::finish) else finish(false)
                            }
                        }
                    }
                }
            }
        }
    }
    private fun phase(g: Long, b: Long, phase: Int, sent: Int, done: (Boolean) -> Unit) {
        if (!current(g, b)) return
        if (phase == PHASES.size) { done(true); return }
        val (frame, total) = PHASES[phase]
        val count = minOf(10, total - sent)
        io.frames(frame, count, { current(g, b) }) { ok ->
            if (!current(g, b)) return@frames
            if (!ok) { done(false); return@frames }
            io.later(0) {
                if (sent + count == total) phase(g, b, phase + 1, 0, done)
                else phase(g, b, phase, sent + count, done)
            }
        }
    }
    // A slow pass keeps its authority; transitions and confirmation coalesce behind it.
    private fun requestPass(g: Long, powerTransition: Boolean) {
        if (!current(g)) return
        if (busy || (powerTransition && confirmationPending)) {
            if (powerTransition) pendingTransition = true else pendingConfirmation = true
        } else {
            pendingConfirmation = false; pendingTransition = false
            pass(g, false)
        }
    }
    private fun drain(g: Long) {
        val requested = pendingConfirmation || (pendingTransition && powerOk && !confirmationPending)
        pendingConfirmation = false
        if (!confirmationPending) pendingTransition = false
        if (requested && current(g)) pass(g, false)
    }

    /** Raw power 3 is an OEM OK state, not evidence that the vehicle is parked. */
    @Synchronized fun observe(rawPower: Int?, platform: Int?, periodic: Boolean, now: Long) {
        if (!active || !recoveryReady) return
        val ok = rawPower == 3
        if (ok != powerOk) {
            powerOk = ok
            if (!ok) pendingTransition = false
            val t = ++transition
            val g = generation
            if (ok) io.later(4_000) {
                if (current(g) && powerOk && transition == t) requestPass(g, true)
            }
        }
        if (!periodic || !ok || platform !in PLATFORMS || busy) return
        val g = generation
        val b = batch
        busy = true
        val valid = { current(g, b) && powerOk }
        val finish = { if (current(g, b)) { busy = false; drain(g) } }
        val send = { if (valid()) io.frames(HIDE, 3, valid) { finish() } else finish() }
        if (lastInit == Long.MIN_VALUE || now - lastInit >= 60_000) {
            lastInit = now
            io.initialize(valid) { if (it) send() else finish() }
        } else send()
    }

    fun restore(done: (Boolean) -> Unit) {
        cancel()
        val g = generation
        val valid = { generation == g && io.valid() }
        fun chunks(sent: Int) {
            if (!valid()) { done(false); return }
            io.frames(RESTORE, 10, valid) { ok ->
                if (!ok || !valid()) { done(false); return@frames }
                if (sent + 10 < 40) chunks(sent + 10)
                else io.command(12, valid) { accepted ->
                    if (!accepted || !valid()) { done(false); return@command }
                    io.later(1_200) {
                        if (valid()) io.command(53, valid, done) else done(false)
                    }
                }
            }
        }
        io.initialize(valid) { initialized ->
            if (!initialized || !valid()) done(false)
            else io.later(1_000) { if (valid()) chunks(0) else done(false) }
        }
    }

    companion object {
        const val HIDE = "23,50,00,08,01,00"
        const val RESTORE = "23,50,00,08,01,01"
        val PHASES = listOf("26,F0,00,28,01,06" to 60, "43,20,00,00,01,01" to 50,
            "43,20,00,21,01,01" to 50, "26,F0,00,28,01,06" to 40, HIDE to 60)
        val FRAMES = (PHASES.map { it.first } + RESTORE).toSet()
        val COMMANDS = setOf(13, 12, 53)
        val PLATFORMS = setOf(102, 115, 141, 181)
    }
}
