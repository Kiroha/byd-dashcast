package com.byd.dashcast.report

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Process-scoped ownership for one Bug Wizard submission across Activity recreation. */
internal object BugWizardSubmissionGate {

    private class Active(val token: String) {
        @Volatile var backgroundWork = false
    }

    private val active = AtomicReference<Active?>()

    /** Claims the submission slot, or returns null while another wizard submission owns it. */
    fun claim(): String? {
        val candidate = Active(UUID.randomUUID().toString())
        return if (active.compareAndSet(null, candidate)) candidate.token else null
    }

    fun activeToken(): String? = active.get()?.token

    fun isActive(token: String): Boolean =
        token.isNotEmpty() && active.get()?.token == token

    fun setBackgroundWork(token: String, running: Boolean) {
        val current = active.get()
        if (current?.token == token) current.backgroundWork = running
    }

    fun hasBackgroundWork(token: String): Boolean {
        val current = active.get()
        return current?.token == token && current.backgroundWork
    }

    fun release(token: String) {
        while (true) {
            val current = active.get() ?: return
            if (current.token != token) return
            if (active.compareAndSet(current, null)) return
        }
    }

    internal fun resetForTest() {
        active.set(null)
    }
}