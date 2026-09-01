package com.byd.dashcast.cluster.display

import android.content.Context

import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.util.AppLogger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Recovers an entered typed projection transact before allowing the global queue to advance. */
internal object ProjectionTransportRecovery {
    private const val TAG = "ProjectionRecovery"
    private const val TYPED_TRANSACTION_TIMEOUT_MS = 20_000L
    private const val RECOVERY_RETRY_MS = 5_000L

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "projection-recovery").apply { isDaemon = true }
    }

    fun watchdog(context: Context, onRecovered: Runnable): ProjectionTypedWatchdog =
        ProjectionTypedWatchdog(
            TYPED_TRANSACTION_TIMEOUT_MS,
            ProjectionTypedWatchdog.Scheduler { delayMs, action ->
                val future: ScheduledFuture<*> = executor.schedule(
                    action, delayMs, TimeUnit.MILLISECONDS)
                ProjectionTypedWatchdog.Cancellable { future.cancel(false) }
            },
            recover = { done -> recoverUntilSafe(context.applicationContext, done) },
            onRecovered = onRecovered,
        )

    private fun recoverUntilSafe(context: Context, done: Runnable) {
        executor.execute {
            if (ProxyClient.terminateHungDaemonViaAdb(context)) {
                done.run()
                return@execute
            }
            AppLogger.e(TAG, "proxy termination not yet confirmed; projection queue stays closed")
            executor.schedule(
                { recoverUntilSafe(context, done) },
                RECOVERY_RETRY_MS,
                TimeUnit.MILLISECONDS,
            )
        }
    }
}