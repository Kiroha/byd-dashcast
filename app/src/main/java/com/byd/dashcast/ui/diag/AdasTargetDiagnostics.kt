package com.byd.dashcast.ui.diag

import com.byd.dashcast.proxy.AdasTargetProperties
import com.byd.dashcast.proxy.AdasTargetProperties.Variant
import com.byd.dashcast.util.AppLogger
import java.util.Timer
import java.util.TimerTask
import java.util.UUID

internal data class AdasTargetRow(
    val property: Int, val status: String = "NOT_ATTEMPTED", val raw: Int? = null,
    val startedEpochMs: Long? = null, val receivedEpochMs: Long? = null,
    val durationMs: Long? = null, val detail: String = ""
) {
    fun summary(): String = "${AdasTargetProperties.hex(property)} ${AdasTargetProperties.label(property)}" +
        " status=$status raw=${raw ?: "unknown"} startedEpochMs=${startedEpochMs ?: "unknown"}" +
        " receivedEpochMs=${receivedEpochMs ?: "unknown"} durationMs=${durationMs ?: "unknown"} $detail"
}

internal data class AdasTargetSnapshot(
    val id: String, val variant: Variant?, val context: String, val startedEpochMs: Long,
    val outcome: String = "RUNNING", val durationMs: Long = 0, val reason: String = "",
    val rows: List<AdasTargetRow> = AdasTargetProperties.allowed.map { AdasTargetRow(it) }
) {
    fun summary(physicalPending: Boolean): String = buildString {
        append("Historical manual diagnostic, non-atomic cache reads; NOT a driving aid.\n")
        append("Evidence: SX326 / 13.1.33.2602030.1; runtime compatibility UNKNOWN.\n")
        append("device=1038; context=original system context; user acknowledged stationary test; movement=UNKNOWN\n")
        append("API=BYDAutoManager.getInt; typed ADAS_GET grant unverified; effective raw endpoint admission only\n")
        append("Read receipt times do not establish sensor freshness. Zero is not proof of clear surroundings.\n")
        append("Four front groups only; rear/side coverage UNKNOWN; units/physical axes UNKNOWN.\n")
        append("id=$id startedEpochMs=$startedEpochMs outcome=$outcome durationMs=$durationMs physicalCallPending=$physicalPending\n")
        append("decoderHypothesis=${variant?.name ?: "UNKNOWN"}; $context\n$reason\n")
        rows.forEach { append(it.summary()).append('\n') }
    }
}

internal data class AdasTargetPoint(val label: String, val type: Int, val x: Double, val y: Double)

/** No unsigned coercion: negative SDK errors must never become plausible coordinates. */
internal fun decodeAdasTargets(snapshot: AdasTargetSnapshot): List<AdasTargetPoint> {
    val variant = snapshot.variant ?: return emptyList()
    if (snapshot.outcome != "COMPLETE") return emptyList()
    val byKey = snapshot.rows.associateBy { it.property }
    return AdasTargetProperties.groups.mapNotNull { group ->
        val rows = AdasTargetProperties.keys(variant, group).map { byKey[it] ?: return@mapNotNull null }
        if (rows.any { it.status != "RAW" || it.raw == null || it.raw < 0 }) return@mapNotNull null
        val (available, type, x, y) = rows.map { it.raw!! }
        if (available != 1 || type !in 1..4) return@mapNotNull null
        if (variant == Variant.NEW) {
            if (x > 240 || y > 1600) null
            else AdasTargetPoint(group.label, type, x * 0.1 - 12.0, y * 0.1 - 10.0)
        } else {
            if (x > 200 || y > 200) null
            else AdasTargetPoint(group.label, type, (x - 100) / 10.0, (y - 100).toDouble())
        }
    }
}

/** One bounded manual sequence. A blocked synchronous Binder call keeps the slot until return.
 * The watchdog retires the sequence; it cannot safely interrupt an OEM/native call. */
internal class AdasTargetCapture(
    private val timeoutMs: Long = 15_000,
    private val claim: () -> Boolean = { true },
    private val release: () -> Unit = {},
    private val log: (String) -> Unit = {},
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallMs: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private var physicalBusy = false
    private var latest: AdasTargetSnapshot? = null
    private var startMono = 0L
    fun snapshot(): AdasTargetSnapshot? = synchronized(lock) { latest }
    fun isBusy(): Boolean = synchronized(lock) { physicalBusy }
    fun summary(): String = synchronized(lock) {
        latest?.summary(physicalBusy) ?: "No ADAS target capture in this process. No OEM reads performed by this report.\n"
    }

    fun start(variant: Variant?, context: String, read: (Int) -> Int): Boolean {
        synchronized(lock) {
            if (physicalBusy || !claim()) return false
            physicalBusy = true
            startMono = monotonicMs()
            latest = AdasTargetSnapshot(UUID.randomUUID().toString(), variant, bounded(context), wallMs())
        }
        var watchdog: Timer? = null
        try {
            val id = snapshot()!!.id
            log("capture started: $id; decoder=${variant?.name ?: "UNKNOWN"}; device=1038; ${bounded(context)}")
            watchdog = Timer("adas-target-deadline", true)
            watchdog.schedule(object : TimerTask() {
                override fun run() = stop("TIMED_OUT", "${timeoutMs}ms logical budget expired; any pending physical call remains quarantined", id)
            }, timeoutMs)
            val timer = watchdog
            Thread({
                try { collect(read) }
                catch (failure: Throwable) { stop("FAILED", errorDetail(failure)) }
                finally {
                    timer.cancel()
                    val ended = synchronized(lock) {
                        val detail = "capture physical worker returned: ${latest?.id}; outcome=${latest?.outcome}"
                        release()
                        physicalBusy = false
                        detail
                    }
                    log(ended)
                }
            }, "adas-target-snapshot").apply { isDaemon = true }.start()
            return true
        } catch (failure: Throwable) {
            watchdog?.cancel()
            stop("FAILED", "Could not start capture: ${errorDetail(failure)}")
            synchronized(lock) { release(); physicalBusy = false }
            return false
        }
    }

    fun cancel(reason: String = "Screen hidden or manual cancellation") = stop("CANCELLED", reason)

    private fun stop(outcome: String, reason: String, expectedId: String? = null) {
        synchronized(lock) {
            val current = latest ?: return
            if (expectedId != null && current.id != expectedId) return
            if (current.outcome != "RUNNING") return
            latest = current.copy(outcome = outcome, reason = bounded(reason), durationMs = monotonicMs() - startMono,
                rows = current.rows.map { if (it.status == "READING") it.copy(status = "PENDING", detail = "Completion unknown; no retry") else it })
            log("capture ${current.id} $outcome: ${bounded(reason)}")
        }
    }

    private fun collect(read: (Int) -> Int) {
        for (property in AdasTargetProperties.allowed) {
            val began: Long
            synchronized(lock) {
                if (latest!!.outcome != "RUNNING") return
                if (monotonicMs() - startMono >= timeoutMs) {
                    stop("TIMED_OUT", "Capture time budget exhausted; remaining keys not attempted")
                    return
                }
                began = monotonicMs()
                update(AdasTargetRow(property, "READING", startedEpochMs = wallMs()))
            }
            val result = try {
                val value = read(property)
                AdasTargetRow(property, if (value < 0) "SENTINEL" else "RAW", raw = value,
                    detail = when (value) {
                        -10011 -> "OEM absent/null cache key"
                        -10013 -> "OEM integer type mismatch"
                        in Int.MIN_VALUE..-1 -> "Negative OEM result; validity unknown"
                        else -> "Raw cache result; reception/freshness not established"
                    })
            } catch (failure: Exception) {
                AdasTargetRow(property, "ERROR", detail = errorDetail(failure))
            }
            synchronized(lock) {
                if (monotonicMs() - startMono >= timeoutMs)
                    stop("TIMED_OUT", "Reply arrived after the capture deadline; ignored for plotting")
                val previous = latest!!.rows.single { it.property == property }
                val row = result.copy(status = if (latest!!.outcome == "RUNNING") result.status else "LATE_${result.status}",
                    startedEpochMs = previous.startedEpochMs, receivedEpochMs = wallMs(), durationMs = monotonicMs() - began)
                update(row)
                log("capture ${latest!!.id} ${row.summary()}")
            }
        }
        synchronized(lock) {
            if (monotonicMs() - startMono >= timeoutMs)
                stop("TIMED_OUT", "Capture deadline reached before completion")
            else stop("COMPLETE", "Snapshot finished; positive raw values still do not prove live sensor data")
        }
    }

    private fun update(row: AdasTargetRow) {
        latest = latest!!.copy(rows = latest!!.rows.map { if (it.property == row.property) row else it })
    }

    companion object {
        private fun bounded(text: String) = text.replace('\n', ' ').replace('\r', ' ').take(768)
        private fun errorDetail(error: Throwable): String {
            var cause = error
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            repeat(16) { if (seen.add(cause)) cause.cause?.let { cause = it } }
            return bounded("${error.javaClass.simpleName} / ${cause.javaClass.name}: ${cause.message.orEmpty()}")
        }
    }
}

/** Report access only formats retained state; it never calls an OEM API. */
internal object AdasTargetDiagnostics {
    val capture = AdasTargetCapture(claim = { DiagActivity.claimAdasDiagnostic() },
        release = { DiagActivity.releaseAdasDiagnostic() }, log = { AppLogger.i("AdasTargetDiag", it) })
    fun snapshot(): String = capture.summary()
}
