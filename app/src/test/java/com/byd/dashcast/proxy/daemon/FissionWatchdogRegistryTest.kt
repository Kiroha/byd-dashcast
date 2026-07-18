package com.byd.dashcast.proxy.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FissionWatchdogRegistryTest {
    @Test
    fun `different Layout packages are guarded concurrently`() {
        val registry = FissionWatchdogRegistry()
        val waze = registry.start("com.waze")
        val newPipe = registry.start("org.schabi.newpipe")

        assertTrue(registry.isCurrent("com.waze", waze))
        assertTrue(registry.isCurrent("org.schabi.newpipe", newPipe))
        assertEquals(2, registry.activePackages())
    }

    @Test
    fun `new launch supersedes only same package guardian`() {
        val registry = FissionWatchdogRegistry()
        val oldWaze = registry.start("com.waze")
        val newPipe = registry.start("org.schabi.newpipe")
        val newWaze = registry.start("com.waze")

        assertFalse(registry.isCurrent("com.waze", oldWaze))
        assertTrue(registry.isCurrent("com.waze", newWaze))
        assertTrue(registry.isCurrent("org.schabi.newpipe", newPipe))

        registry.finish("com.waze", oldWaze)
        assertTrue(registry.isCurrent("com.waze", newWaze))
        registry.finish("com.waze", newWaze)
        assertFalse(registry.isCurrent("com.waze", newWaze))
    }

    @Test
    fun `teardown cancellation removes only requested package`() {
        val registry = FissionWatchdogRegistry()
        val waze = registry.start("com.waze")
        val newPipe = registry.start("org.schabi.newpipe")

        assertTrue(registry.cancel("com.waze"))
        assertFalse(registry.isCurrent("com.waze", waze))
        assertTrue(registry.isCurrent("org.schabi.newpipe", newPipe))
        assertFalse(registry.cancel("com.waze"))
    }

    @Test
    fun `full layout deactivation cancels every package guardian`() {
        val registry = FissionWatchdogRegistry()
        val waze = registry.start("com.waze")
        val newPipe = registry.start("org.schabi.newpipe")

        assertEquals(2, registry.cancelAll())
        assertFalse(registry.isCurrent("com.waze", waze))
        assertFalse(registry.isCurrent("org.schabi.newpipe", newPipe))
        assertEquals(0, registry.activePackages())
    }
}