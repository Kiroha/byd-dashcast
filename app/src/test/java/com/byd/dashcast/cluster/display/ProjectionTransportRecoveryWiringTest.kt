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
            "app/src/main/java/com/byd/dashcast/proxy/ProxyClient.kt").readText()

        assertTrue(bus.contains("ProjectionTransportRecovery.watchdog"))
        assertTrue(bus.contains("if (watchdog.shouldAbortFallback()) return"))
        val transport = File(root,
            "app/src/main/java/com/byd/dashcast/cluster/display/ProjectionTransportRecovery.kt")
            .readText()
        assertTrue(transport.contains("ProxyClient.captureDaemonIdentity()"))
        assertTrue(transport.contains("terminateHungDaemonViaAdb(context, identity)"))
        val typed = adb.substringAfter("if (typedObserver != null) {")
            .substringBefore("if (isAdbTransportUnreachable())")
        assertTrue(adb.contains("typedObserver == null || ProxyClient.supportsProtocol(25)"))
        assertTrue(typed.contains("ProxyClient.setNonBlockingReconnect(true)"))
        assertTrue(typed.contains("typedObserver.shouldAbortFallback()"))
        assertTrue(typed.contains("fallback suppressed"))
        val recovery = proxy.substringAfter("fun terminateHungDaemonViaAdb")
            .substringBefore("// ─── Auto-recovery helpers")
        assertTrue(recovery.contains("executeShellWithResultBlocking"))
        assertTrue(recovery.contains("INSTANCE_CHANGED"))
        assertTrue(recovery.contains("sBinder === expected.binder"))
        assertTrue(recovery.contains("kill -9"))
        // indexOf-only ordering passes VACUOUSLY when either side is deleted (-1 < n).
        val killed = recovery.indexOf("KILLED")
        val cleared = recovery.indexOf("sBinder = null")
        assertTrue("the recovery must assert the kill happened", killed >= 0)
        assertTrue("and must clear the cached binder afterwards", cleared >= 0)
        assertTrue(killed < cleared)
    }
}