package com.byd.dashcast.proxy

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProxyInstanceMarkerWiringTest {

    @Test
    fun `v25 daemon persists instance marker before publishing binder`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/proxy/daemon/ProxyDaemonMain.java").isFile }
        val daemon = File(root,
            "app/src/main/java/com/byd/dashcast/proxy/daemon/ProxyDaemonMain.java").readText()

        assertTrue(daemon.indexOf("if (!writeInstanceFile())") <
            daemon.indexOf("sBinder = new ProxyBinder()"))
        assertTrue(daemon.indexOf("if (!writeInstanceFile())") <
            daemon.indexOf("releaseStartupLock()"))
        assertTrue(daemon.indexOf("releaseStartupLock()") <
            daemon.indexOf("sBinder = new ProxyBinder()"))
        assertTrue(daemon.contains("System.exit(4)"))
        assertTrue(daemon.contains("reply.writeString(INSTANCE_TOKEN)"))
        val heartbeat = daemon.substringAfter("private static void installSelfHealHeartbeat()")
            .substringBefore("private static void healTriggerFile()")
        assertTrue(heartbeat.contains("healInstanceFile()"))

        val cleanup = daemon.substringAfter("new Thread(\"pid-cleanup\")")
            .substringBefore("// shutdown hooks may be disallowed")
        assertTrue(cleanup.indexOf("ProxyDaemonStartupLock.tryAcquire") <
            cleanup.indexOf("readSmallFile(new File(PID_FILE))"))
        assertTrue(cleanup.indexOf("readSmallFile(new File(PID_FILE))") <
            cleanup.indexOf("new File(PID_FILE).delete()"))
        assertTrue(cleanup.contains("cleanupLock.close()"))
    }
}