package com.byd.dashcast.infrastructure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SurfaceDaemonStartCommandTest {
    @Test
    fun `launcher detaches descriptors and returns explicit marker`() {
        val command = SurfaceDaemonStartCommand.build(
            "/data/app/com.byd.dashcast/base.apk",
            "/data/local/tmp/mirrordaemon.log",
            "/data/local/tmp/mirrordaemon_latest.log"
        )

        assertTrue(command.contains("setsid sh -c"))
        assertTrue(command.contains("</dev/null >/dev/null 2>&1 &"))
        assertTrue(command.endsWith("; echo STARTED"))
    }

    /**
     * Pins the two identities the spawn depends on. The `--nice-name` is a WIRE identifier
     * (AdbLocalClient.DAEMON_GREP matches it to reuse or kill the daemon) and must survive any
     * class rename; the fully-qualified class name is the app_process entry point and must track
     * the Java class, or the daemon never starts.
     */
    @Test
    fun `launcher pins the process nice-name and the app_process entry point`() {
        val command = SurfaceDaemonStartCommand.build(
            "/data/app/com.byd.dashcast/base.apk",
            "/data/local/tmp/mirrordaemon.log",
            "/data/local/tmp/mirrordaemon_latest.log"
        )

        assertTrue(command.contains("--nice-name=byd.mirror.daemon"))
        assertTrue(command.contains("com.byd.dashcast.proxy.daemon.SurfaceDaemon"))
    }

    @Test
    fun `generated launcher has valid POSIX shell syntax`() {
        val shell = File("/bin/sh")
        assumeTrue(shell.canExecute())
        val command = SurfaceDaemonStartCommand.build(
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
