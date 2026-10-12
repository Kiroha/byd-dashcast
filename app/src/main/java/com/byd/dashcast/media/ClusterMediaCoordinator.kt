package com.byd.dashcast.media

import java.io.Closeable

/** One process-wide owner and writer, shared by production and the explicitly acknowledged bench. */
internal class MediaOutputOwnership(private val writer: () -> ClusterMediaWriter) {
    private var owner: Any? = null
    private var cleanup = MediaCleanupMode.PAUSE
    private val availability = LinkedHashMap<Any, () -> Unit>()

    @Synchronized fun acquire(token: Any, mode: MediaCleanupMode = MediaCleanupMode.PAUSE): Boolean {
        if (owner != null) return owner === token && cleanup == mode
        owner = token
        cleanup = mode
        return true
    }

    @Synchronized fun publish(token: Any, data: ClusterMediaData,
                              result: ((MediaWriteResult) -> Unit)? = null) {
        if (owner === token) writer().publish(data, cleanup, result)
    }

    @Synchronized fun sourceLost(token: Any) {
        if (owner === token) writer().publish(null, cleanup)
    }

    fun release(token: Any, result: ((MediaWriteResult) -> Unit)? = null) {
        val callbacks = synchronized(this) {
            if (owner !== token) return
            owner = null
            writer().publish(null, cleanup, result)
            availability.values.toList()
        }
        // Production dispatches refresh to its main Handler: never hold this ownership lock while
        // entering another lifecycle lock, and never wait behind a media Binder transaction.
        callbacks.forEach { try { it() } catch (_: Exception) {} }
    }

    @Synchronized fun observeAvailability(callback: () -> Unit): Closeable {
        val token = Any()
        availability[token] = callback
        return Closeable { synchronized(this) { availability.remove(token) } }
    }
}

internal object ClusterMediaCoordinator {
    private val writer by lazy {
        ClusterMediaWriter(ProxyClusterMediaTransport(), ExecutorMediaScheduler(), report = { result ->
            MediaDiagnostics.store.result(result)
            if (result == MediaWriteResult.UNAVAILABLE || result == MediaWriteResult.CLEAR_FAILED) {
                // Enum only: never metadata, session identity, package names, or exception messages.
                com.byd.dashcast.util.AppLogger.w("ClusterMedia", "output=$result")
            }
        }, diagnostic = { stage, rc -> MediaDiagnostics.store.output(stage, rc) })
    }
    val ownership = MediaOutputOwnership { writer }
}
