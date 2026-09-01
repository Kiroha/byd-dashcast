package com.byd.dashcast.cluster.display

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProjectionTransportRecoveryWiringTest {

    @Test
    fun `typed timeout kills old daemon and suppresses old reconnect and fallback`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/cluster/display/ProjectionCommandSequencer.kt").isFile }
        val bus = File(root,
            "app/src/main/java/com/byd/dashcast/cluster/display/ProjectionCommandSequencer.kt")
            .readText()
        val adb = File(root,
            "app/src/main/java/com/byd/dashcast/infrastructure/AdbLocalClient.java").readText()
        val proxy = File(root,
            "app/src/main/java/com/byd/dashcast/proxy/ProxyClient.java").readText()

        assertTrue(bus.contains("ProjectionTransportRecovery.watchdog"))
        assertTrue(bus.contains("if (watchdog.shouldAbortFallback()) return"))
        val typed = adb.substringAfter("if (typedObserver != null) {")
            .substringBefore("if (isAdbTransportUnreachable())")
        assertTrue(typed.contains("ProxyClient.setNonBlockingReconnect(true)"))
        assertTrue(typed.contains("typedObserver.shouldAbortFallback()"))
        assertTrue(typed.contains("fallback suppressed"))
        val recovery = proxy.substringAfter("public static boolean terminateHungDaemonViaAdb")
            .substringBefore("// ─── Auto-recovery helpers")
        assertTrue(recovery.contains("executeShellWithResultBlocking"))
        assertTrue(recovery.contains("kill -9"))
        assertTrue(recovery.indexOf("KILLED") < recovery.indexOf("sBinder = null"))
    }
}