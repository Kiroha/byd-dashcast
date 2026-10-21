package com.byd.dashcast.ui.diag

import com.byd.dashcast.proxy.AdasLogProbePolicy
import com.byd.dashcast.util.AppLogger
import java.util.Timer
import java.util.TimerTask
import java.util.UUID

internal data class AdasLogSnapshot(val id: String, val startedEpochMs: Long, val context: String,
    val outcome: String = "RUNNING", val detail: String = "", val result: String = "", val receivedEpochMs: Long? = null) {
    fun summary(pending: Boolean) = "Historical OEM log excerpt — freshness unknown. Not a driving aid.\n" +
        "Inspection time only: startedEpochMs=$startedEpochMs receivedEpochMs=${receivedEpochMs ?: "unknown"}; not source/sensor time.\n" +
        "id=$id outcome=$outcome physicalCallPending=$pending; $context\n$detail\n$result\n"
}

/** One manual physical call; logical timeout/cancel cannot interrupt Binder or release its slot early. */
internal class AdasLogCapture(
    private val timeoutMs: Long = AdasLogProbePolicy.CAPTURE_BUDGET_MS,
    private val claim: () -> Boolean = { true }, private val release: () -> Unit = {},
    private val log: (String) -> Unit = {}, private val wallMs: () -> Long = { System.currentTimeMillis() },
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val lock = Any()
    private var busy = false
    private var latest: AdasLogSnapshot? = null
    fun isBusy(): Boolean = synchronized(lock) { busy }
    fun snapshot(): AdasLogSnapshot? = synchronized(lock) { latest }
    fun summary(): String = synchronized(lock) {
        latest?.summary(busy) ?: "No existing OEM log inspection in this process. Report performs no new reads.\n"
    }
    fun start(context: String, read: () -> String): Boolean {
        val began = monotonicMs()
        val id = synchronized(lock) {
            if (busy || !claim()) return false
            busy = true
            UUID.randomUUID().toString().also { latest = AdasLogSnapshot(it, wallMs(), context.take(1024)) }
        }
        var timer: Timer? = null
        try {
            log("inspection started id=$id; ${context.take(1024)}")
            timer = Timer("adas-log-deadline", true)
            timer.schedule(object : TimerTask() { override fun run() { stop("TIMED_OUT", "Logical deadline; pending physical call remains quarantined", id) } }, timeoutMs)
            val watchdog = timer
            Thread({
                try {
                    // Cancellation can win before admission; no physical call is made then.
                    val admitted = synchronized(lock) {
                        if (monotonicMs() - began >= timeoutMs) stop("TIMED_OUT", "Budget expired before physical admission", id)
                        latest?.id == id && latest?.outcome == "RUNNING"
                    }
                    if (admitted) {
                        val result = read()
                        require(result.length <= AdasLogProbePolicy.MAX_RESPONSE) { "Oversized OEM log reply" }
                        synchronized(lock) {
                            if (monotonicMs() - began >= timeoutMs) stop("TIMED_OUT", "Reply arrived after the logical deadline", id)
                            val current = latest!!
                            latest = current.copy(outcome = if (current.outcome == "RUNNING") "COMPLETE" else "LATE_${current.outcome}",
                                result = result, receivedEpochMs = wallMs())
                        }
                    }
                } catch (error: Throwable) {
                    synchronized(lock) {
                        val current = latest!!
                        val cause = generateSequence(error) { it.cause }.take(16).last()
                        latest = current.copy(outcome = if (current.outcome == "RUNNING") "FAILED" else "LATE_${current.outcome}",
                            detail = "${error.javaClass.simpleName} / ${cause.javaClass.simpleName}: ${cause.message.orEmpty()}".take(768), receivedEpochMs = wallMs())
                    }
                } finally {
                    watchdog.cancel()
                    synchronized(lock) {
                        release(); busy = false
                        log("inspection returned id=$id outcome=${latest?.outcome} resultChars=${latest?.result?.length}; ${latest?.detail}")
                    }
                }
            }, "adas-existing-log-probe").apply { isDaemon = true }.start()
            return true
        } catch (error: Throwable) {
            timer?.cancel()
            stop("FAILED", "Worker startup failed: ${error.javaClass.simpleName}", id)
            synchronized(lock) { release(); busy = false }
            return false
        }
    }
    fun cancel(reason: String) = stop("CANCELLED", reason)
    private fun stop(outcome: String, reason: String, expectedId: String? = null) = synchronized(lock) {
        val current = latest ?: return@synchronized
        if (expectedId != null && current.id != expectedId || current.outcome != "RUNNING") return@synchronized
        latest = current.copy(outcome = outcome, detail = reason.take(768))
        log("inspection ${current.id} $outcome: ${reason.take(768)}")
    }
}

internal object AdasLogDiagnostics {
    val capture = AdasLogCapture(claim = { DiagActivity.claimAdasDiagnostic() },
        release = { DiagActivity.releaseAdasDiagnostic() }, log = { AppLogger.i("AdasLogDiag", it) })
    fun snapshot(): String = capture.summary()
}
