package com.byd.dashcast.proxy.daemon

/**
 * Owns a slot's display binding, never its holder Surface.
 *
 * Availability, creation and display operations run on one existing lifecycle lane (the
 * daemon's main looper). [close] and readiness reads may run on Binder/death-recipient
 * threads. Closing invalidates readiness immediately; disposal is dispatched to that lane.
 * No framework operation or dispatch runs while [lock] is held.
 */
internal class SlotSurfaceLifecycle<S : Any, D : Any>(
    private val isSurfaceValid: (S) -> Boolean,
    private val displayId: (D) -> Int,
    private val isDisplayValid: (D) -> Boolean,
    private val bind: (D, S?) -> Unit,
    private val releaseDisplay: (D) -> Unit,
    private val dispatch: (() -> Unit) -> Unit,
    private val onClosed: (Throwable?) -> Unit,
    private val onBindingChanged: (Long, Boolean) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private var closed = false
    private var generation = 0L
    private var current: S? = null
    private var display: D? = null
    private var id = -1
    private var boundGeneration = -1L

    val isClosed: Boolean get() = synchronized(lock) { closed }

    /** The newest valid holder, including callbacks received before display creation. */
    fun currentSurface(): S? {
        val snapshot = synchronized(lock) {
            if (closed) return null
            Pair(generation, current ?: return null)
        }
        if (!validSurface(snapshot.second)) return null
        return synchronized(lock) {
            if (!closed && generation == snapshot.first && current === snapshot.second)
                snapshot.second else null
        }
    }

    /** A resource check at the time of the call; this says nothing about fresh pixels. */
    fun readyDisplayId(): Int {
        val snapshot = synchronized(lock) {
            if (closed || id <= 0 || boundGeneration != generation) return -1
            Triple(display ?: return -1, current ?: return -1, generation)
        }
        if (!validSurface(snapshot.second) || !validDisplay(snapshot.first)) return -1
        return synchronized(lock) {
            if (!closed && display === snapshot.first && current === snapshot.second &&
                generation == snapshot.third && boundGeneration == generation) id else -1
        }
    }

    /** Called on the lifecycle lane. Duplicate surfaceChanged callbacks do not rebind. */
    fun available(surface: S) {
        if (!validSurface(surface)) {
            destroyed()
            return
        }
        val target: D?
        val version: Long
        synchronized(lock) {
            if (closed) return
            if (current === surface && boundGeneration == generation && display != null) return
            current = surface
            generation++
            version = generation
            boundGeneration = -1
            target = display
        }
        if (target != null) bindCurrent(target, surface, version)
    }

    /** Invalidates readiness before asking the existing display to stop using the holder. */
    fun destroyed() {
        val target: D?
        val version: Long
        synchronized(lock) {
            if (closed) return
            current = null
            generation++
            version = generation
            boundGeneration = -1
            target = display
        }
        onBindingChanged(version, false)
        if (target == null) return
        try {
            bind(target, null)
        } catch (error: Exception) {
            close(error)
        }
    }

    /**
     * Creates on the lifecycle lane, then adopts the newest generation before committing.
     * A concurrent timeout/close owns no local display yet: this method disposes that late
     * result itself. A holder lost during creation makes the whole slot fail closed.
     */
    fun create(factory: (S) -> D?): Int {
        val initial = currentSurface() ?: return -1
        if (synchronized(lock) { closed || display != null }) return -1
        val created = try { factory(initial) } catch (error: Exception) {
            close(error)
            return -1
        } ?: run {
            close(IllegalStateException("display creation returned null"))
            return -1
        }
        val createdId = try { displayId(created) } catch (_: Exception) { -1 }
        if (createdId <= 0 || !validDisplay(created)) {
            dispose(created)
            close(IllegalStateException("invalid display after creation: $createdId"))
            return -1
        }
        val latest: S?
        val version: Long
        val accepted = synchronized(lock) {
            latest = current
            version = generation
            if (closed || display != null) false else {
                display = created
                id = createdId
                true
            }
        }
        if (!accepted) {
            dispose(created)
            return -1
        }
        if (latest == null || !validSurface(latest)) {
            close(IllegalStateException("holder lost during display creation"))
            return -1
        }
        // Even the same Surface wrapper can have acquired a new native generation while
        // createVirtualDisplay ran. Always set the latest holder once before publication.
        bindCurrent(created, latest, version)
        return readyDisplayId()
    }

    /** Serialized resize access. A released/superseded display is never handed to callers. */
    fun withDisplay(operation: (D) -> Unit): Boolean {
        val target = synchronized(lock) { if (closed) null else display } ?: return false
        if (!validDisplay(target)) return false
        operation(target)
        return synchronized(lock) { !closed && display === target }
    }

    /** Idempotent, immediate logical invalidation; all resource disposal stays on the lane. */
    fun close(reason: Throwable? = null) {
        val owned: D?
        synchronized(lock) {
            if (closed) return
            closed = true
            current = null
            generation++
            boundGeneration = -1
            id = -1
            owned = display
            display = null
        }
        dispatch {
            try { if (owned != null) dispose(owned) }
            finally { onClosed(reason) }
        }
    }

    private fun bindCurrent(target: D, surface: S, version: Long) {
        if (synchronized(lock) { closed || display !== target || generation != version }) return
        try {
            if (!validDisplay(target) || !validSurface(surface)) {
                close(IllegalStateException("invalid resource before surface bind"))
                return
            }
            bind(target, surface)
            val committed = synchronized(lock) {
                if (!closed && display === target && current === surface && generation == version) {
                    boundGeneration = version
                    true
                } else false
            }
            if (committed) onBindingChanged(version, true)
        } catch (error: Exception) {
            close(error)
        }
    }

    private fun validSurface(surface: S): Boolean =
        try { isSurfaceValid(surface) } catch (_: Exception) { false }

    private fun validDisplay(value: D): Boolean =
        try { isDisplayValid(value) } catch (_: Exception) { false }

    private fun dispose(value: D) {
        try { releaseDisplay(value) } catch (_: Exception) {
            // Ownership is still revoked; a failing platform release must not resurrect it.
        }
    }
}
