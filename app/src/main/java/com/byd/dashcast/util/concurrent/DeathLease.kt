package com.byd.dashcast.util.concurrent

import java.util.concurrent.atomic.AtomicBoolean

/** Links one transient resource owner to a cleanup callback, exactly once. */
class DeathLease private constructor(
    private val owner: Owner,
    private val cleanup: Runnable,
) : AutoCloseable {

    interface Owner {
        @Throws(Exception::class)
        fun link(deathCallback: Runnable)
        fun unlink(deathCallback: Runnable)
    }

    private val deathCallback = Runnable { ownerDied() }
    private val active = AtomicBoolean(true)

    val isActive: Boolean get() = active.get()

    private fun ownerDied() {
        if (!active.compareAndSet(true, false)) return
        try {
            cleanup.run()
        } finally {
            owner.unlink(deathCallback)
        }
    }

    /** Ends explicit ownership without invoking death cleanup; the normal owner performs teardown. */
    override fun close() {
        if (!active.compareAndSet(true, false)) return
        owner.unlink(deathCallback)
    }

    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun attach(owner: Owner?, cleanup: Runnable?): DeathLease {
            requireNotNull(owner) { "owner required" }
            requireNotNull(cleanup) { "cleanup required" }
            val lease = DeathLease(owner, cleanup)
            owner.link(lease.deathCallback)
            return lease
        }
    }
}
