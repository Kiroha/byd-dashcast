package com.byd.dashcast.media

import java.io.Closeable
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** The only permitted instrument properties. No navigation, HUD switch, or display-mode writes. */
object ClusterMediaProperties {
    const val SOURCE = 871366704
    const val TEXT = 1140527112
    const val STATE = 1138753546
    const val ONLINE = 11
    const val PLAYING = 1
    const val PAUSED = 2
}

interface ClusterMediaTransport {
    fun connection(): Any?
    fun isCurrent(connection: Any): Boolean
    fun integer(connection: Any, property: Int, value: Int): Int
    fun bytes(connection: Any, property: Int, value: ByteArray): Int
}

fun interface MediaCancellation { fun cancel() }
interface MediaScheduler : Closeable {
    fun schedule(delayMs: Long, action: () -> Unit): MediaCancellation
}

internal class ExecutorMediaScheduler : MediaScheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "cluster-media-writer").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setKeepAliveTime(30, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }
    override fun schedule(delayMs: Long, action: () -> Unit): MediaCancellation {
        val future = executor.schedule({ action() }, delayMs, TimeUnit.MILLISECONDS)
        return MediaCancellation { future.cancel(false) }
    }
    override fun close() { executor.shutdown() }
}

enum class MediaWriteResult { ACCEPTED, UNAVAILABLE, CLEARED, CLEAR_FAILED, NOTHING_TO_CLEAR }

/**
 * Independent, bounded latest-value writer. A cancellable 50 ms gap mirrors Commander's observed
 * contract; it is not a firmware guarantee. No SDK work runs on the notification/navigation thread.
 * A failed write is not retried, and cleanup never reconnects or guesses the previous OEM source.
 */
class ClusterMediaWriter(
    private val transport: ClusterMediaTransport,
    private val scheduler: MediaScheduler,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val report: (MediaWriteResult) -> Unit = {},
    private val diagnostic: (String, Int?) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private var generation = 0L
    private var desired: ClusterMediaData? = null
    private var pending: MediaCancellation? = null
    private var failedDelivery = false
    private var lastAttemptMs: Long? = null
    // Only the serialized worker reads/writes ownership. It is not proof of current OEM ownership:
    // another radio/media app can take over these shared properties; hardware coexistence is pending.
    private var ownedConnection: Any? = null
    private var lastDelivered: ClusterMediaData? = null

    fun publish(data: ClusterMediaData?, result: ((MediaWriteResult) -> Unit)? = null) {
        synchronized(lock) {
            if (data == desired && result == null) {
                // A new session callback may retry a failed value after a bounded cooldown. There
                // is no timer/replay loop, daemon reconnect, or retry of an in-flight transaction.
                if (data == null || !failedDelivery ||
                    lastAttemptMs?.let { nowMs() - it < 5000 } == true) return
            }
            desired = data
            failedDelivery = false
            val epoch = ++generation
            pending?.cancel()
            pending = scheduler.schedule(0) {
                if (data == null) clear(epoch, result) else start(epoch, data, result)
            }
        }
    }

    private fun current(epoch: Long): Boolean = synchronized(lock) { generation == epoch }
    private fun valid(epoch: Long, connection: Any): Boolean = current(epoch) && transport.isCurrent(connection)

    private inline fun sdk(stage: String, action: () -> Int): Int {
        diagnostic(stage, null)
        val rc = action()
        diagnostic(stage, rc)
        return rc
    }

    private fun start(epoch: Long, data: ClusterMediaData, result: ((MediaWriteResult) -> Unit)?) {
        if (!current(epoch)) return
        synchronized(lock) { lastAttemptMs = nowMs() }
        try {
            diagnostic("CONNECTION", null)
            val connection = transport.connection()
            if (connection == null || !valid(epoch, connection)) {
                ownedConnection = null
                lastDelivered = null
                notify(epoch, result, MediaWriteResult.UNAVAILABLE)
                return
            }
            if (ownedConnection !== connection) lastDelivered = null
            if (lastDelivered == data) { notify(epoch, result, MediaWriteResult.ACCEPTED); return }
            if (sdk("SOURCE") { transport.integer(connection, ClusterMediaProperties.SOURCE, ClusterMediaProperties.ONLINE) } != 0) {
                fail(epoch, result)
                return
            }
            // Selecting ONLINE is already a partial acquisition, even if cancellation or text
            // rejection follows. Cleanup clears that acquisition without guessing an OEM source.
            ownedConnection = connection
            lastDelivered = null
            synchronized(lock) {
                if (!valid(epoch, connection)) {
                    notify(epoch, result, MediaWriteResult.UNAVAILABLE)
                    return
                }
                pending = scheduler.schedule(50) { finish(epoch, connection, data, result) }
            }
        } catch (_: Exception) {
            diagnostic("EXCEPTION", null)
            fail(epoch, result)
        }
    }

    private fun finish(epoch: Long, connection: Any, data: ClusterMediaData,
                       result: ((MediaWriteResult) -> Unit)?) {
        if (!valid(epoch, connection)) { notify(epoch, result, MediaWriteResult.UNAVAILABLE); return }
        try {
            val text = ClusterMediaEncoding.text(data.title, data.artist)
            if (!valid(epoch, connection)) { notify(epoch, result, MediaWriteResult.UNAVAILABLE); return }
            if (sdk("TEXT") { transport.bytes(connection, ClusterMediaProperties.TEXT, text) } != 0) {
                fail(epoch, result)
                return
            }
            ownedConnection = connection
            lastDelivered = null
            if (!valid(epoch, connection)) { notify(epoch, result, MediaWriteResult.UNAVAILABLE); return }
            val state = if (data.playing) ClusterMediaProperties.PLAYING else ClusterMediaProperties.PAUSED
            if (sdk("STATE") { transport.integer(connection, ClusterMediaProperties.STATE, state) } != 0) {
                fail(epoch, result)
                return
            }
            lastDelivered = data
            notify(epoch, result, MediaWriteResult.ACCEPTED)
        } catch (_: Exception) {
            diagnostic("EXCEPTION", null)
            fail(epoch, result)
        }
    }

    private fun fail(epoch: Long, result: ((MediaWriteResult) -> Unit)?) {
        // A failed replacement must not leave the previous title or a new title with stale state.
        // Exactly one best-effort cleanup, against the still-current acquired connection only.
        if (current(epoch) && ownedConnection != null) clear(epoch, null)
        notify(epoch, result, MediaWriteResult.UNAVAILABLE)
    }

    private fun clear(epoch: Long, result: ((MediaWriteResult) -> Unit)?) {
        if (!current(epoch)) return
        val connection = ownedConnection
        ownedConnection = null
        lastDelivered = null
        if (connection == null || !valid(epoch, connection)) {
            notify(epoch, result, if (connection == null) MediaWriteResult.NOTHING_TO_CLEAR else MediaWriteResult.CLEAR_FAILED)
            return
        }
        try {
            // Clear only the acquired music channel. Do not select ONLINE again or restore a
            // guessed source; successful SOURCE alone also counts as partial acquisition.
            if (sdk("CLEAR_TEXT") { transport.bytes(connection, ClusterMediaProperties.TEXT, ClusterMediaEncoding.text("", "")) } != 0) {
                notify(epoch, result, MediaWriteResult.CLEAR_FAILED)
                return
            }
            if (!valid(epoch, connection)) {
                notify(epoch, result, MediaWriteResult.CLEAR_FAILED)
                return
            }
            val rc = sdk("CLEAR_STATE") { transport.integer(connection, ClusterMediaProperties.STATE, ClusterMediaProperties.PAUSED) }
            notify(epoch, result, if (rc == 0) MediaWriteResult.CLEARED else MediaWriteResult.CLEAR_FAILED)
        } catch (_: Exception) {
            notify(epoch, result, MediaWriteResult.CLEAR_FAILED)
        }
    }

    private fun notify(epoch: Long, callback: ((MediaWriteResult) -> Unit)?, result: MediaWriteResult) {
        synchronized(lock) {
            if (!current(epoch)) return
            failedDelivery = result == MediaWriteResult.UNAVAILABLE
        }
        try { report(result) } catch (_: Exception) {}
        try { callback?.invoke(result) } catch (_: Exception) { /* UI cannot stall writes. */ }
    }
}
