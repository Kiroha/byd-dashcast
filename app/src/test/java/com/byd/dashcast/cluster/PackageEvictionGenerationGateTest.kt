package com.byd.dashcast.cluster

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageEvictionGenerationGateTest {
    @Test
    fun `launch winning the race invalidates eviction before destructive dispatch`() {
        val gate = PackageEvictionGenerationGate()
        val token = gate.beginEviction("com.example.nav")
        var launched = false

        assertTrue(gate.prepareLaunch("com.example.nav", Runnable { launched = true }))
        assertFalse(gate.tryBeginDestructive(token))
        assertFalse(launched)
    }

    @Test
    fun `destructive dispatch winning the race owns deferred launch until terminal callback`() {
        val gate = PackageEvictionGenerationGate()
        val token = gate.beginEviction("com.example.nav")
        var launched = false
        var staleOwnershipMutation = false

        assertTrue(gate.tryBeginDestructive(token))
        assertFalse(gate.prepareLaunch("com.example.nav", Runnable { launched = true }))
        assertFalse(launched)

        val completion = gate.finishDestructive(
            token, Runnable { staleOwnershipMutation = true }
        )
        assertFalse(staleOwnershipMutation)
        completion!!.deferredLaunch!!.run()
        assertTrue(launched)
    }
}