package com.byd.dashcast.ui.diag

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** At most one outstanding physical call in this process, including after a client timeout.
 * Binder calls cannot be safely cancelled. A timed-out call stays quarantined until it returns;
 * idle callbacks keep the screen's shared diagnostic gate occupied during that interval. */
internal class DiLinkContextTransport(
    private val timeoutMs: Long = DiLinkContextPlan.TRANSPORT_TIMEOUT_MS,
    private val newThread: (Runnable) -> Thread = { task -> Thread(task, "dilink-context-binder").apply { isDaemon = true } },
) {
    data class Reply(val exitCode: Int?, val combinedOutput: String, val error: String = "", val fatal: Boolean = false)
    private val lock = Any()
    private var active = false
    private val idleCallbacks = mutableListOf<() -> Unit>()

    fun isBusy(): Boolean = synchronized(lock) { active }

    fun whenIdle(callback: () -> Unit) {
        val now = synchronized(lock) {
            if (active) { idleCallbacks.add(callback); false } else true
        }
        if (now) callback()
    }

    fun execute(call: () -> Reply): Reply {
        synchronized(lock) {
            if (active) return Reply(null, "", "A previous transport call is still pending; no command submitted", true)
        }
        // Allocate everything before claiming the gate. Allocation failure (including Error)
        // cannot leave it occupied when there is no physical call to release it.
        val task = FutureTask {
            try { call() }
            finally { release() }
        }
        val thread = newThread(task)
        check(thread.state == Thread.State.NEW) { "Transport thread must not be started by its factory" }
        synchronized(lock) {
            if (active) return Reply(null, "", "A previous transport call is still pending; no command submitted", true)
            active = true
        }
        try {
            thread.start()
        } catch (failure: Throwable) {
            release()
            if (failure !is Exception) throw failure
            return Reply(null, "", "Cannot start transport: ${failure.javaClass.simpleName}: ${failure.message}", true)
        }
        return try { task.get(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) {
            Reply(null, "", "Transport did not return in ${timeoutMs / 1000}s; call remains pending, no retry", true)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Reply(null, "", "Collector interrupted; transport completion is unknown", true)
        } catch (e: ExecutionException) {
            Reply(null, "", "Transport failed: ${e.cause?.javaClass?.simpleName}: ${e.cause?.message}", true)
        }
    }

    private fun release() {
        val callbacks = synchronized(lock) {
            active = false
            idleCallbacks.toList().also { idleCallbacks.clear() }
        }
        callbacks.forEach { runCatching { it() } }
    }
}
