package com.byd.dashcast.cluster

/** Accepts each completion once until all arrive or timeout atomically closes the operation. */
class BoundedCompletionBarrier(
    count: Int,
    private val onClosed: Runnable,
) {
    private var remaining = count.coerceAtLeast(0)
    private var closed = false

    fun complete(onAccepted: Runnable): Boolean {
        val finish: Boolean
        synchronized(this) {
            if (closed) return false
            onAccepted.run()
            if (remaining > 0) remaining--
            finish = remaining == 0
            if (finish) closed = true
        }
        if (finish) onClosed.run()
        return true
    }

    fun timeout(): Boolean {
        synchronized(this) {
            if (closed) return false
            closed = true
        }
        onClosed.run()
        return true
    }
}