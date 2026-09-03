package com.byd.dashcast.cluster.display

import com.byd.dashcast.infrastructure.AdbLocalClient
import java.util.concurrent.atomic.AtomicInteger

/** Times one typed projection transact and aborts fallback only after recovery takes ownership. */
internal class ProjectionTypedWatchdog(
    private val timeoutMs: Long,
    private val scheduler: Scheduler,
    private val recover: (Runnable) -> Unit,
    private val onRecovered: Runnable,
    private val beforeStart: () -> Unit = {},
) : AdbLocalClient.TypedDispatchObserver {

    fun interface Cancellable {
        fun cancel()
    }

    fun interface Scheduler {
        fun schedule(delayMs: Long, action: Runnable): Cancellable
    }

    private val state = AtomicInteger(IDLE)
    @Volatile private var timeout: Cancellable? = null

    override fun onStart() {
        if (!state.compareAndSet(IDLE, RUNNING)) return
        beforeStart()
        timeout = scheduler.schedule(timeoutMs, Runnable {
            if (!state.compareAndSet(RUNNING, TIMED_OUT)) return@Runnable
            recover(onRecovered)
        })
    }

    override fun onFinish() {
        if (state.compareAndSet(RUNNING, FINISHED)) timeout?.cancel()
    }

    override fun shouldAbortFallback(): Boolean = state.get() == TIMED_OUT

    companion object {
        private const val IDLE = 0
        private const val RUNNING = 1
        private const val FINISHED = 2
        private const val TIMED_OUT = 3
    }
}