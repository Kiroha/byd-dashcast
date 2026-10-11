package com.byd.dashcast.media

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.Closeable

/** Session collection remains independent; only presentation yields to accepted cluster guidance. */
internal class MediaListenerLifecycle(
    private val enabled: () -> Boolean,
    private val ownership: MediaOutputOwnership,
    private val dispatchRefresh: (() -> Unit) -> Unit = { it() },
    private val retryScheduler: MediaScheduler? = null,
    private val diagnostics: MediaDiagnosticStore = MediaDiagnosticStore(),
    private val requestSnapshot: (Closeable, (ClusterMediaData?) -> Unit) -> Unit = { _, _ -> },
    private val observeSessions: ((ClusterMediaData?) -> Unit, () -> Unit) -> Closeable,
) : Closeable {
    private val token = Any()
    private var connected = false
    private var destroyed = false
    private var sessions: Closeable? = null
    private var observerGeneration = 0L
    private var waiting: Closeable? = null
    private var retry: MediaCancellation? = null
    private var failures = 0
    private var starting = false
    private val presentationObserver = ownership.observePresentation { blocked, revision ->
        if (!blocked) dispatchRefresh {
            synchronized(this) {
                if (!destroyed && connected && enabled()) {
                    if (sessions == null) refresh() else resumeFromFreshSnapshot(revision)
                }
            }
        }
    }

    @Synchronized fun connected() { if (destroyed) return; connected = true; diagnostics.listener(true); refresh() }
    @Synchronized fun disconnected() { connected = false; diagnostics.listener(false); stop(); failures = 0 }

    @Synchronized fun refresh() {
        if (destroyed || !connected || !enabled()) { stop(); failures = 0; return }
        if (sessions != null || starting || retry != null) return
        if (!ownership.acquire(token, observeWhileSuppressed = true)) {
            diagnostics.busy()
            if (waiting == null) waiting = ownership.observeAvailability {
                dispatchRefresh { refresh() }
            }
            // Close the release-before-subscription race without polling or timed retries.
            if (!ownership.acquire(token, observeWhileSuppressed = true)) return
        }
        waiting?.close()
        waiting = null
        val generation = ++observerGeneration
        diagnostics.begin()
        starting = true
        try {
            val observed = observeSessions({ data ->
                synchronized(this) {
                    if (!destroyed && connected && enabled() && observerGeneration == generation) {
                        failures = 0 // Even an empty successful collection proves the observer is healthy.
                        if (data == null) ownership.sourceLost(token) else ownership.publish(token, data)
                    }
                }
            }, {
                synchronized(this) { if (observerGeneration == generation) failed() }
            })
            // A synchronous authorization failure can invalidate the start before it returns.
            if (observerGeneration == generation && !destroyed) sessions = observed else observed.close()
        } catch (error: Exception) {
            if (observerGeneration == generation) {
                diagnostics.failure(diagnostics.generation(), error is SecurityException)
                failed()
            }
        } finally {
            starting = false
            // Also close unblock-during-construction: the queued presentation callback may have
            // observed starting=true before the session subscription was returned.
            ownership.resumeRevision(token)?.let { resumeFromFreshSnapshot(it) }
        }
    }

    private fun resumeFromFreshSnapshot(revision: Long) {
        val current = sessions ?: return
        val generation = observerGeneration
        try {
            requestSnapshot(current) { data ->
                synchronized(this) {
                    if (!destroyed && connected && enabled() && observerGeneration == generation) {
                        ownership.resume(token, revision, data)
                    }
                }
            }
        } catch (error: Exception) {
            diagnostics.failure(diagnostics.generation(), error is SecurityException)
            failed()
        }
    }

    private fun failed() {
        stop()
        if (destroyed || !connected || !enabled()) return
        failures = (failures + 1).coerceAtMost(5)
        val delay = longArrayOf(1000, 2000, 5000, 15000, 30000)[failures - 1]
        diagnostics.waiting(delay, failures)
        val generation = observerGeneration
        retry = retryScheduler?.schedule(delay) {
            synchronized(this) {
                if (generation != observerGeneration) return@synchronized
                retry = null
                refresh()
            }
        }
    }

    private fun stop() {
        retry?.cancel()
        retry = null
        diagnostics.stop()
        ++observerGeneration // Reject callbacks already in flight before unregistering.
        waiting?.close()
        waiting = null
        val previous = sessions
        sessions = null
        try { previous?.close() } catch (_: Exception) {}
        ownership.release(token)
    }

    @Synchronized override fun close() {
        destroyed = true; connected = false; diagnostics.listener(false); stop(); failures = 0
        presentationObserver.close()
    }
}

class LocalMediaController(context: Context, component: ComponentName) : Closeable {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val retryScheduler = object : MediaScheduler {
        override fun schedule(delayMs: Long, action: () -> Unit): MediaCancellation {
            val task = Runnable { action() }
            handler.postDelayed(task, delayMs)
            return MediaCancellation { handler.removeCallbacks(task) }
        }
        override fun close() = Unit
    }
    private val lifecycle = MediaListenerLifecycle({ MediaPrefs.isEnabled(app) },
        ClusterMediaCoordinator.ownership, { action -> handler.post { action() } },
        retryScheduler, MediaDiagnostics.store,
        { observer, snapshot -> (observer as MediaSessionObserver).refresh(snapshot) }) { changed, failed ->
        val generation = MediaDiagnostics.store.generation()
        MediaSessionObserver(app, component, changed, failed, { count, valid, present, playing ->
            MediaDiagnostics.store.collection(generation, count, valid, present, playing)
        }, { denied -> MediaDiagnostics.store.failure(generation, denied) })
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
