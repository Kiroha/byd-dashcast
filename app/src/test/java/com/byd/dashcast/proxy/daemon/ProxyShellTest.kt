package com.byd.dashcast.proxy.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyShellTest {

    @Test
    fun timeoutStartsAtProcessSpawnEvenWhenStdoutStaysOpen() {
        val startedAt = System.nanoTime()
        val result = ProxyShell.exec("printf ready; exec tail -f /dev/null", 200, 1024)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(-1, result.exit)
        assertEquals("ERR timeout 200ms", result.output)
        assertTrue("elapsed=$elapsedMs ms", elapsedMs < 2_000)
    }

    @Test
    fun outputIsCappedWhilePipeContinuesToDrain() {
        val result = ProxyShell.exec("yes x | head -c 4096", 2_000, 128)

        assertEquals(0, result.exit)
        assertTrue(result.output.length < 256)
        assertTrue(result.output.endsWith("[output truncated]"))
    }

    @Test
    fun normalOutputKeepsLegacyTrailingNewlineSemantics() {
        val result = ProxyShell.exec("printf 'one\\ntwo\\n'", 2_000, 1024)

        assertEquals(0, result.exit)
        assertEquals("one\ntwo", result.output)
    }
}
