package com.byd.dashcast.proxy.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProxyDaemonStartupLockTest {
    @Test
    fun `one startup owns complete pid publication through nonce phase`() {
        val directory = Files.createTempDirectory("proxy-startup-lock").toFile()
        try {
            val lockFile = File(directory, "startup.lock")
            val pidFile = File(directory, "proxy.pid")
            pidFile.writeText("9999")
            val first = ProxyDaemonStartupLock.tryAcquire(lockFile)
            assertNotNull(first)
            assertNull(ProxyDaemonStartupLock.tryAcquire(lockFile))

            first!!.publishPid(pidFile, "1234")
            assertEquals("1234", pidFile.readText())
            assertNull(ProxyDaemonStartupLock.tryAcquire(lockFile))

            first.close()
            ProxyDaemonStartupLock.tryAcquire(lockFile)!!.close()
        } finally {
            directory.deleteRecursively()
        }
    }
}