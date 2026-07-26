package com.byd.dashcast.util.concurrent

/** Owns one closeable native resource and defers release while callers actively use it. */
class CloseableResourceSlot<T>(private val closer: Closer<T>) {

    fun interface Closer<T> {
        @Throws(Throwable::class)
        fun close(resource: T)
    }

    private var resource: T? = null
    private var users = 0
    private var released = false

    /** Publishes the resource or closes it immediately when release already won. */
    fun publish(value: T): Boolean {
        var closeNow: T? = null
        synchronized(this) {
            if (released || resource != null) {
                closeNow = value
            } else {
                resource = value
                return true
            }
        }
        closeQuietly(closeNow)
        return false
    }

    /** Acquires the current resource for use, or null once absent/released. */
    @Synchronized
    fun acquire(): T? {
        if (released || resource == null) return null
        users++
        return resource
    }

    /** Ends one use and performs a deferred release when this was the final user. */
    fun releaseUse(expected: T) {
        var closeNow: T? = null
        synchronized(this) {
            if (resource != expected || users <= 0) return
            users--
            if (released && users == 0) {
                closeNow = resource
                resource = null
            }
        }
        closeQuietly(closeNow)
    }

    /** Marks the slot terminal and closes now or after the final active user. */
    fun release() {
        var closeNow: T? = null
        synchronized(this) {
            if (released) return
            released = true
            if (users == 0) {
                closeNow = resource
                resource = null
            }
        }
        closeQuietly(closeNow)
    }

    @Synchronized
    fun hasResource(): Boolean = !released && resource != null

    @Synchronized
    fun isReleased(): Boolean = released

    private fun closeQuietly(value: T?) {
        if (value == null) return
        try {
            closer.close(value)
        } catch (ignored: Throwable) {
        }
    }
}
