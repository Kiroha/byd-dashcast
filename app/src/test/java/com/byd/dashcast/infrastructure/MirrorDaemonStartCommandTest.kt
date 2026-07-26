package com.byd.dashcast.infrastructure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class MirrorDaemonStartCommandTest {
    @Test
    fun `launcher detaches descriptors and returns explicit marker`() {
        val command = MirrorDaemonStartCommand.build(
            "/data/app/com.byd.dashcast/base.apk",
            "/data/local/tmp/mirrordaemon.log",
            "/data/local/tmp/mirrordaemon_latest.log"
        )

        assertTrue(command.contains("setsid sh -c"))
        assertTrue(command.contains("</dev/null >/dev/null 2>&1 &"))
        assertTrue(command.endsWith("; echo STARTED"))
    }

    @Test
    fun `generated launcher has valid POSIX shell syntax`() {
        val shell = File("/bin/sh")
        assumeTrue(shell.canExecute())
        val command = MirrorDaemonStartCommand.build(
            "/data/app/a'b/base.apk",
            "/data/local/tmp/mirror log.txt",
            "/data/local/tmp/mirror latest.log"
        )
        val process = ProcessBuilder(shell.path, "-n", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }

        assertEquals(output, 0, process.waitFor())
    }
}