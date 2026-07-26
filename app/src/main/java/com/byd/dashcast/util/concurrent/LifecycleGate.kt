package com.byd.dashcast.util.concurrent

import java.util.concurrent.atomic.AtomicBoolean

/** One-way lifecycle gate for invalidating asynchronous work owned by a destroyed component. */
class LifecycleGate {

    private val active = AtomicBoolean(true)

    fun capture(): Token = Token(active)

    fun invalidate() {
        active.set(false)
    }

    class Token internal constructor(private val active: AtomicBoolean) {
        // Boolean `is`-property: Kotlin callers use `token.isValid`, and Kotlin still
        // generates the Java accessor `isValid()` for the Java call sites.
        val isValid: Boolean get() = active.get()
    }
}
