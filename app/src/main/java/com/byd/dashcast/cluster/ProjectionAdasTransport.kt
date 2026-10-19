package com.byd.dashcast.cluster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.byd.dashcast.proxy.ProxyClient
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded one-attempt IPC. A timeout is uncertainty, not permission to replay a write. */
internal class ProjectionAdasTransport(private val context: Context,
    private val binderProvider: () -> IBinder? = { ProxyClient.getProxyDaemonBinder() },
    private val restoring: () -> Boolean = { false }) {
    private val main = Handler(Looper.getMainLooper())
    private fun lane(name: String) = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, name).apply { isDaemon = true } })
    private val reads = lane("adas-read")
    private val writes = lane("adas-write")
    private val recovery = lane("adas-recovery")
    private var shellFallback = false

    fun <T> call(valid: () -> Boolean, timeout: Long = 5_000,
                 work: (IBinder) -> T, mutation: Boolean = false, done: (T?) -> Unit) {
        val finished = AtomicBoolean()
        val deadline = Runnable { if (finished.compareAndSet(false, true)) done(null) }
        main.postDelayed(deadline, timeout)
        try {
            (if (!mutation) reads else if (restoring()) recovery else writes).execute {
                val result = try {
                    if (!valid() || finished.get()) null
                    else binderProvider()?.let {
                        if (valid() && ProxyClient.getProxyDaemonBinder() === it && it.isBinderAlive) work(it) else null
                    }
                } catch (_: Exception) { null }
                main.post {
                    if (finished.compareAndSet(false, true)) {
                        main.removeCallbacks(deadline)
                        done(result)
                    }
                }
            }
        } catch (_: Exception) {
            main.removeCallbacks(deadline)
            if (finished.compareAndSet(false, true)) done(null)
        }
    }
    fun shell(command: String, valid: () -> Boolean, done: (Boolean) -> Unit) =
        call(valid, work = { binder ->
            AdasCommandResult.accepted(ProxyClient.runShellOnce(binder, "$command && echo DASHCAST_ADAS_OK"))
        }, mutation = true, done = { done(it == true) })

    fun initialize(valid: () -> Boolean, done: (Boolean) -> Unit) = shell(
        "cmd deviceidle whitelist +com.byd.clusterdebug >/dev/null 2>&1; " +
            "am startservice -n com.byd.clusterdebug/.ClusterDebugService", valid, done)

    fun command(code: Int, valid: () -> Boolean, done: (Boolean) -> Unit) {
        require(code in ProjectionAdasPlan.COMMANDS)
        // Select the service before mutating it. A lost response never causes a second transaction.
        val command = "if service check AutoContainer | grep -q 'not found'; then " +
            "service call auto_container 2 i32 1000 i32 $code s16 ''; else " +
            "service call AutoContainer 2 i32 1000 i32 $code s16 ''; fi"
        shell(command, valid, done)
    }

    fun frames(frame: String, count: Int, valid: () -> Boolean, done: (Boolean) -> Unit) {
        require(frame in ProjectionAdasPlan.FRAMES && count in 1..10)
        val end = SystemClock.uptimeMillis() + 5_000
        var remaining = count
        var finished = false
        lateinit var timeout: Runnable
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            main.removeCallbacks(timeout)
            done(ok)
        }
        lateinit var send: () -> Unit
        send = send@{
            if (finished) return@send
            if (!valid() || SystemClock.uptimeMillis() >= end) { finish(false); return@send }
            if (remaining == 0) { finish(true); return@send }
            if (shellFallback) {
                // One frame per call lets restore cancel the compatibility path between frames.
                shell("am broadcast -a com.byd.cluster.spi -p com.byd.clusterdebug " +
                    "--es wholeFrame '$frame' >/dev/null 2>&1", valid) { ok ->
                    if (!finished) {
                        if (ok && valid()) { remaining--; send() } else finish(false)
                    }
                }
            } else {
                val accepted = AtomicBoolean()
                try {
                    context.sendOrderedBroadcast(Intent("com.byd.cluster.spi")
                        .setPackage("com.byd.clusterdebug")
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtra("wholeFrame", frame), null, object : BroadcastReceiver() {
                            override fun onReceive(context: Context?, intent: Intent?) {
                                if (!finished && accepted.compareAndSet(false, true)) {
                                    if (!valid()) { finish(false); return }
                                    remaining--
                                    send()
                                }
                            }
                        }, main, 0, null, null)
                } catch (_: SecurityException) {
                    if (remaining == count) { shellFallback = true; send() } else finish(false)
                } catch (_: RuntimeException) { finish(false) }
            }
        }
        timeout = Runnable { finish(false) }
        main.postDelayed(timeout, 5_000)
        send()
    }
}

internal object AdasCommandResult {
    fun accepted(output: String): Boolean {
        if (!output.lineSequence().any { it.trim() == "DASHCAST_ADAS_OK" }) return false
        val lower = output.lowercase()
        if (listOf("error:", "permission denial", "permission denied", "not found", "securityexception",
                "remoteexception", "exception code", "failed", "unknown service", "does not exist", "cannot find service", "can't find service").any(lower::contains)) return false
        val status = Regex("Parcel\\(\\s*([0-9a-fA-F]{8})").find(output)?.groupValues?.get(1)
        return status == null || status == "00000000"
    }
}
