package com.byd.dashcast.ime

/** Versioned pending text so an older IME completion cannot clear a newer edit. */
internal class ImePendingText {
    class Snapshot(
        @JvmField val text: CharSequence?,
        @JvmField val generation: Long
    )

    private var text: CharSequence? = null
    private var generation: Long = 0

    @Synchronized
    fun set(value: CharSequence?) {
        text = value
        generation++
    }

    @Synchronized
    fun snapshot(): Snapshot {
        return Snapshot(text, generation)
    }

    @Synchronized
    fun clearIfCurrent(expectedGeneration: Long): Boolean {
        if (generation != expectedGeneration) return false
        text = null
        generation++
        return true
    }

    @Synchronized
    fun clear() {
        text = null
        generation++
    }
}
