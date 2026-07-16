package com.byd.dashcast.util.concurrent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DeathLeaseTest {

    @Test
    fun ownerDeathRunsCleanupExactlyOnce() {
        val owner = FakeOwner()
        val cleanupCount = AtomicInteger()
        val lease = DeathLease.attach(owner) { cleanupCount.incrementAndGet() }

        owner.die()
        owner.die()
        lease.close()

        assertEquals(1, cleanupCount.get())
        assertEquals(0, owner.linkedCallbacks)
    }

    @Test
    fun explicitCloseUnlinksWithoutRunningDeathCleanup() {
        val owner = FakeOwner()
        val cleanupCount = AtomicInteger()
        val lease = DeathLease.attach(owner) { cleanupCount.incrementAndGet() }

        lease.close()
        owner.die()

        assertEquals(0, cleanupCount.get())
        assertEquals(0, owner.linkedCallbacks)
    }

    private class FakeOwner : DeathLease.Owner {
        private var callback: Runnable? = null
        var linkedCallbacks = 0
            private set

        override fun link(deathCallback: Runnable) {
            callback = deathCallback
            linkedCallbacks = 1
        }

        override fun unlink(deathCallback: Runnable) {
            if (callback === deathCallback) {
                callback = null
                linkedCallbacks = 0
            }
        }

        fun die() {
            val current = callback
            callback = null
            linkedCallbacks = 0
            current?.run()
        }
    }
}
