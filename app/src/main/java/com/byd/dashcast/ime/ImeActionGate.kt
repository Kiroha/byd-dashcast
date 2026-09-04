package com.byd.dashcast.ime

import java.util.concurrent.atomic.AtomicBoolean

/** Single-flight lifecycle gate for an asynchronous IME action and its completion. */
internal class ImeActionGate {
    fun interface Completion {
        fun onComplete(accepted: Boolean)
    }

    class Operation internal constructor(private val completion: Completion) {
        private val completed = AtomicBoolean()

        fun complete(accepted: Boolean) {
            if (!completed.compareAndSet(false, true)) return
            completion.onComplete(accepted)
        }
    }

    private var current: Operation? = null

    @Synchronized
    fun begin(completion: Completion): Operation? {
        if (current != null) return null
        val operation = Operation(completion)
        current = operation
        return operation
    }

    @Synchronized
    fun isCurrent(operation: Operation?): Boolean = operation != null && current === operation

    fun finish(operation: Operation, accepted: Boolean) {
        val wasCurrent = synchronized(this) {
            val was = current === operation
            if (was) current = null
            was
        }
        operation.complete(accepted && wasCurrent)
    }

    fun cancelCurrent() {
        val operation = synchronized(this) {
            val op = current
            current = null
            op
        }
        if (operation != null) operation.complete(false)
    }
}
