package com.byd.dashcast.media

import java.io.Closeable

/** One process-wide owner and writer, shared by production and the explicitly acknowledged bench. */
internal class MediaOutputOwnership(
    private val presentationChanged: (Boolean) -> Unit = {},
    private val writer: () -> ClusterMediaWriter,
) {
    private var owner: Any? = null
    private val availability = LinkedHashMap<Any, () -> Unit>()
    private val presentation = LinkedHashMap<Any, (Boolean, Long) -> Unit>()
    private var guidance = false
    private var presentationRevision = 0L
    private var readyRevision: Long? = null

    @Synchronized fun acquire(token: Any, observeWhileSuppressed: Boolean = false): Boolean {
        if (guidance && !observeWhileSuppressed) return false
        if (owner != null && owner !== token) return false
        if (owner !== token) readyRevision = if (guidance) null else presentationRevision
        owner = token
        return true
    }

    @Synchronized fun publish(token: Any, data: ClusterMediaData,
                              result: ((MediaWriteResult) -> Unit)? = null) {
        if (owner === token && !guidance && readyRevision == presentationRevision) {
            writer().publish(data, result)
        } else if (owner === token) result?.invoke(MediaWriteResult.UNAVAILABLE)
    }

    @Synchronized fun sourceLost(token: Any) {
        if (owner === token) writer().publish(null)
    }

    fun release(token: Any, result: ((MediaWriteResult) -> Unit)? = null) {
        val callbacks = synchronized(this) {
            if (owner !== token) return
            owner = null
            readyRevision = null
            writer().publish(null, result)
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

    /** Called only after accepted cluster guidance, or the existing navigation close path. */
    fun clusterGuidance(active: Boolean) {
        val callbacks: List<(Boolean, Long) -> Unit>
        val revision: Long
        synchronized(this) {
            if (guidance == active) return
            guidance = active
            revision = ++presentationRevision
            readyRevision = null
            // Invalidate pending text/state immediately, without waiting for a Binder transaction.
            // Default-off navigation must not instantiate a media writer or scheduler.
            if (active && owner != null) writer().publish(null)
            presentationChanged(active)
            callbacks = presentation.values.toList()
        }
        callbacks.forEach { try { it(active, revision) } catch (_: Exception) {} }
    }

    @Synchronized fun observePresentation(callback: (Boolean, Long) -> Unit): Closeable {
        val token = Any()
        presentation[token] = callback
        return Closeable { synchronized(this) { presentation.remove(token) } }
    }

    /** A resume ticket permits only a fresh session read for the currently unblocked revision. */
    @Synchronized fun resume(token: Any, revision: Long, data: ClusterMediaData?) {
        if (owner !== token || guidance || presentationRevision != revision) return
        readyRevision = revision
        writer().publish(data)
    }

    @Synchronized fun isGuidanceSuppressed() = guidance

    @Synchronized fun resumeRevision(token: Any): Long? =
        if (owner === token && !guidance && readyRevision != presentationRevision) presentationRevision else null
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
    val ownership = MediaOutputOwnership({ MediaDiagnostics.store.guidance(it) }) { writer }
}
