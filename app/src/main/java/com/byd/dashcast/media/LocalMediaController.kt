package com.byd.dashcast.media

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.Closeable

/** Testable lifecycle gate independent of all guidance settings, watchdogs, and source selection. */
internal class MediaListenerLifecycle(
    private val enabled: () -> Boolean,
    private val ownership: MediaOutputOwnership,
    private val dispatchRefresh: (() -> Unit) -> Unit = { it() },
    private val observeSessions: ((ClusterMediaData?) -> Unit, () -> Unit) -> Closeable,
) : Closeable {
    private val token = Any()
    private var connected = false
    private var destroyed = false
    private var sessions: Closeable? = null
    private var observerGeneration = 0L
    private var waiting: Closeable? = null

    @Synchronized fun connected() { connected = true; refresh() }
    @Synchronized fun disconnected() { connected = false; stop() }

    @Synchronized fun refresh() {
        if (destroyed || !connected || !enabled()) { stop(); return }
        if (sessions != null) return
        if (!ownership.acquire(token)) {
            if (waiting == null) waiting = ownership.observeAvailability {
                dispatchRefresh { refresh() }
            }
            // Close the release-before-subscription race without polling or timed retries.
            if (!ownership.acquire(token)) return
        }
        waiting?.close()
        waiting = null
        val generation = ++observerGeneration
        try {
            val observed = observeSessions({ data ->
                synchronized(this) {
                    if (!destroyed && connected && enabled() && observerGeneration == generation) {
                        if (data == null) ownership.sourceLost(token) else ownership.publish(token, data)
                    }
                }
            }, {
                synchronized(this) { if (observerGeneration == generation) stop() }
            })
            // A synchronous authorization failure can invalidate the start before it returns.
            if (observerGeneration == generation && !destroyed) sessions = observed else observed.close()
        } catch (_: Exception) { stop() }
    }

    private fun stop() {
        ++observerGeneration // Reject callbacks already in flight before unregistering.
        waiting?.close()
        waiting = null
        val previous = sessions
        sessions = null
        try { previous?.close() } catch (_: Exception) {}
        ownership.release(token)
    }

    @Synchronized override fun close() { destroyed = true; connected = false; stop() }
}

class LocalMediaController(context: Context, component: ComponentName) : Closeable {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val lifecycle = MediaListenerLifecycle({ MediaPrefs.isEnabled(app) },
        ClusterMediaCoordinator.ownership, { action -> handler.post { action() } }) { changed, failed ->
        MediaSessionObserver(app, component, changed, failed)
    }
    // A lightweight preference observer is necessary for opt-in. MediaSession observers/writes are
    // never acquired while the default-off setting is disabled.
    private val preferenceObserver = MediaPrefs.observe(app) { lifecycle.refresh() }
    fun onConnected() = lifecycle.connected()
    fun onDisconnected() = lifecycle.disconnected()
    override fun close() {
        preferenceObserver.close()
        lifecycle.close()
        handler.removeCallbacksAndMessages(null)
    }
}
