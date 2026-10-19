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
    private var powerOk = false
    private var transition = 0L
    private var lastInit = Long.MIN_VALUE
    private var firstDelivery: ((Boolean) -> Unit)? = null

    @Synchronized fun cancel() { generation++; batch++; transition++; active = false; busy = false; recoveryReady = false; firstDelivery = null }
    @Synchronized fun start(initialRawPower: Int? = null, delivered: ((Boolean) -> Unit)? = null) {
        cancel()
        firstDelivery = delivered
        active = true
        powerOk = initialRawPower == 3
        lastInit = Long.MIN_VALUE
        val g = generation
        fun arm(retry: Boolean) {
            io.journal { saved ->
                if (!current(g)) return@journal
                if (saved) { recoveryReady = true; pass(g, true); layout(g, 0) }
                else {
                    delivery(false)
                    if (retry) io.later(12_000) { if (current(g)) arm(false) }
                }
            }
        }
        arm(true)
    }
    @Synchronized private fun current(g: Long) = active && generation == g && io.valid()
    @Synchronized private fun current(g: Long, b: Long) = current(g) && batch == b
    private fun delivery(ok: Boolean) {
        val callback = firstDelivery ?: return
        firstDelivery = null
        callback(ok)
    }

    private fun pass(g: Long, retry: Boolean) {
        if (!current(g)) return
        val b = ++batch
        busy = true
        val valid = { current(g, b) }
        fun finish() {
            if (!valid()) return
            busy = false
            if (retry) io.later(12_000) { if (valid()) pass(g, false) }
        }
        io.journal { saved ->
            if (!valid()) return@journal
            if (!saved) { delivery(false); finish(); return@journal }
            io.command(13, valid) { commandOk ->
                if (!valid()) return@command
                if (!commandOk) { delivery(false); finish(); return@command }
                io.initialize(valid) { initialized ->
                    if (!valid()) return@initialize
                    if (!initialized) { delivery(false); finish(); return@initialize }
                    lastInit = io.now()
                    io.later(1_000) {
                        if (valid()) io.frames(HIDE, 3, valid) { ok ->
                            if (valid()) {
                                delivery(ok)
                                if (ok) phase(g, b, 0, 0, ::finish) else finish()
                            }
                        }
                    }
                }
            }
        }
    }
    private fun phase(g: Long, b: Long, phase: Int, sent: Int, done: () -> Unit) {
        if (!current(g, b)) return
        if (phase == PHASES.size) { done(); return }
        val (frame, total) = PHASES[phase]
        val count = minOf(10, total - sent)
        io.frames(frame, count, { current(g, b) }) { ok ->
            if (!current(g, b)) return@frames
            if (!ok) { done(); return@frames }
            io.later(0) {
                if (sent + count == total) phase(g, b, phase + 1, 0, done)
                else phase(g, b, phase, sent + count, done)
            }
        }
    }
    private fun layout(g: Long, step: Int) {
        io.later(if (step <= 1) 500 else 1_000) {
            if (current(g)) io.command(LAYOUT[step], { current(g) }) {
                if (current(g)) {
                    if (step == LAYOUT.lastIndex) pass(g, true) else layout(g, step + 1)
                }
            }
        }
    }

    /** Raw power 3 is an OEM OK state, not evidence that the vehicle is parked. */
    @Synchronized fun observe(rawPower: Int?, platform: Int?, periodic: Boolean, now: Long) {
        if (!active || !recoveryReady) return
        val ok = rawPower == 3
        if (ok != powerOk) {
            powerOk = ok
            val t = ++transition
            val g = generation
            if (ok) io.later(4_000) {
                if (current(g) && powerOk && transition == t) pass(g, true)
            }
        }
        if (!periodic || !ok || platform !in PLATFORMS || busy) return
        val g = generation
        val b = batch
        busy = true
        val valid = { current(g, b) && powerOk }
        val finish = { if (current(g, b)) busy = false }
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
        chunks(0)
    }

    companion object {
        const val HIDE = "23,50,00,08,01,00"
        const val RESTORE = "23,50,00,08,01,01"
        val PHASES = listOf("26,F0,00,28,01,06" to 60, "43,20,00,00,01,01" to 50,
            "43,20,00,21,01,01" to 50, "26,F0,00,28,01,06" to 40, HIDE to 60)
        val FRAMES = (PHASES.map { it.first } + RESTORE).toSet()
        val LAYOUT = listOf(18, 0, 16, 35)
        val COMMANDS = (LAYOUT + listOf(13, 12, 53)).toSet()
        val PLATFORMS = setOf(102, 115, 141, 181)
    }
}
