package com.byd.dashcast.proxy.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

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

    @Test
    fun timeoutTerminatesDescendantHoldingStdoutOpen() {
        val pidFile = Files.createTempFile("proxy-shell-child-", ".pid")
        Files.deleteIfExists(pidFile)
        var childPid: Long? = null
        try {
            val command = "sleep 10 & echo \$! > '${pidFile.toAbsolutePath()}'"
            val result = ProxyShell.exec(command, 200, 1024)
            assertEquals(-1, result.exit)

            val pid = String(Files.readAllBytes(pidFile)).trim().toLong()
            childPid = pid
            assertTrue("descendant pid=$pid survived timeout", !isProcessAlive(pid))
        } finally {
            childPid?.let { ProcessBuilder("sh", "-c", "kill -9 $it 2>/dev/null || true").start().waitFor() }
            Files.deleteIfExists(pidFile)
        }
    }

    private fun isProcessAlive(pid: Long): Boolean =
        ProcessBuilder("sh", "-c", "kill -0 $pid 2>/dev/null").start().waitFor() == 0
}
