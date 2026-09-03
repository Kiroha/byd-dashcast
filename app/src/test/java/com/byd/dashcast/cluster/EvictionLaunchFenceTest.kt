package com.byd.dashcast.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvictionLaunchFenceTest {
    @Test
    fun `launches wait for every submitted workflow and latest package request wins`() {
        val fence = EvictionLaunchFence()
        val launched = mutableListOf<String>()
        fence.beginOperation()
        fence.beginOperation()

        assertFalse(fence.prepareLaunch("a", Runnable { launched += "old-a" }))
        assertFalse(fence.prepareLaunch("b", Runnable { launched += "b" }))
        assertFalse(fence.prepareLaunch("a", Runnable { launched += "new-a" }))
        fence.finishOperation()
        assertTrue(launched.isEmpty())

        fence.finishOperation()
        assertEquals(listOf("new-a", "b"), launched)
        assertTrue(fence.prepareLaunch("c", Runnable {}))
    }

    @Test
    fun `one failed deferred launch cannot strand later dispatches`() {
        val fence = EvictionLaunchFence()
        var secondRan = false
        fence.beginOperation()
        assertFalse(fence.prepareLaunch("a", Runnable { error("dispatch failure") }))
        assertFalse(fence.prepareLaunch("b", Runnable { secondRan = true }))

        fence.finishOperation()

        assertTrue(secondRan)
        assertTrue(fence.prepareLaunch("c", Runnable {}))
    }
}