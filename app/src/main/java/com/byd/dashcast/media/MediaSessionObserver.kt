package com.byd.dashcast.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Android notification access is the authorization; no accessibility or notification heuristics. */
internal class MediaSessionObserver(
    context: Context,
    private val component: ComponentName,
    private val changed: (ClusterMediaData?) -> Unit,
    private val failed: () -> Unit,
    private val collected: (Int, Int, Boolean, Int) -> Unit = { _, _, _, _ -> },
    private val failure: (Boolean) -> Unit = {},
) : Closeable {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val thread = HandlerThread("cluster-media-sessions").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)
    private val registered = LinkedHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var selected: Any? = null
    private var activeListenerRegistered = false
    private val snapshotLock = Any()
    private var snapshotQueued = false
    private var pendingSnapshot: ((ClusterMediaData?) -> Unit)? = null
    private val activeListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        guarded { replace(sessions.orEmpty()) }
    }

    init {
        handler.post {
            guarded {
                checkNotNull(manager)
                manager.addOnActiveSessionsChangedListener(activeListener, component, handler)
                activeListenerRegistered = true
                replace(manager.getActiveSessions(component))
            }
        }
    }

    /** Coalesced worker rescan: resume never replays an earlier metadata snapshot. */
    fun refresh(snapshot: (ClusterMediaData?) -> Unit) {
        synchronized(snapshotLock) {
            if (closed.get()) return
            pendingSnapshot = snapshot
            if (snapshotQueued) return
            snapshotQueued = true
        }
        handler.post {
            val completion = synchronized(snapshotLock) {
                snapshotQueued = false
                pendingSnapshot.also { pendingSnapshot = null }
            }
            guarded {
                val data = replace(checkNotNull(manager).getActiveSessions(component))
                if (!closed.get()) completion?.invoke(data)
            }
        }
    }

    private fun replace(controllers: List<MediaController>): ClusterMediaData? {
        val bounded = controllers.take(32) // System/app input must not create an unbounded observer set.
        val tokens = bounded.map { it.sessionToken }.toSet()
        val iterator = registered.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in tokens) {
                entry.value.first.unregisterCallback(entry.value.second)
                iterator.remove()
            }
        }
        for (controller in bounded) {
            val token = controller.sessionToken
            if (token in registered) continue
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) { guarded { emit() } }
                override fun onPlaybackStateChanged(state: PlaybackState?) { guarded { emit() } }
                override fun onSessionDestroyed() {
                    guarded {
                        registered.remove(token)?.let { it.first.unregisterCallback(it.second) }
                        emit()
                    }
                }
            }
            controller.registerCallback(callback, handler)
            registered[token] = controller to callback
        }
        return emit()
    }

    private fun emit(): ClusterMediaData? {
        if (closed.get()) return null
        val candidates = registered.map { (token, pair) ->
            val controller = pair.first
            val metadata = controller.metadata
            MediaCandidate(token, metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                when (controller.playbackState?.state) {
                    PlaybackState.STATE_PLAYING -> MediaPlayback.PLAYING
                    PlaybackState.STATE_PAUSED -> MediaPlayback.PAUSED
                    else -> MediaPlayback.OTHER
                })
        }
        val data = MediaSourceSelector.select(candidates, selected)
        selected = data?.source
        if (!closed.get()) {
            collected(candidates.size, candidates.count { ClusterMediaEncoding.sanitize(it.title).isNotEmpty() && it.playback != MediaPlayback.OTHER }, data != null,
                candidates.count { it.playback == MediaPlayback.PLAYING })
            changed(data)
        }
        return data
    }

    private fun guarded(action: () -> Unit) {
        if (closed.get()) return
        try { action() } catch (error: Exception) {
            failure(error is SecurityException)
            close()
            failed() // Never log the exception message: a media app can embed metadata in it.
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(snapshotLock) { pendingSnapshot = null }
        handler.post {
            if (activeListenerRegistered) try {
                manager?.removeOnActiveSessionsChangedListener(activeListener)
            } catch (_: Exception) { /* Revoked permission; still release local resources. */ }
            activeListenerRegistered = false
            registered.values.forEach { (controller, callback) ->
                try { controller.unregisterCallback(callback) } catch (_: Exception) {}
            }
            registered.clear()
            selected = null
            thread.quitSafely()
        }
    }
}
