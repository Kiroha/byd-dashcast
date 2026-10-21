package com.byd.dashcast.cluster

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OriginClusterStopWiringTest {
    @Test fun `origin stop owns cleanup before eviction and completes outside Activity lifecycle guards`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/java/com/byd/dashcast/MainActivity.kt").isFile }
        val source = File(root, "app/src/main/java/com/byd/dashcast/MainActivity.kt").readText()
        val entry = source.substringAfter("private fun originCluster()")
            .substringBefore("private fun continueOriginCluster")
        assertTrue(entry.indexOf("ProjectionAdasCoordinator.prepareStop") <
            entry.indexOf("FissionOrchestrator.stopAutoOrchestrator"))
        val callbacks = source.substringAfter("private fun continueOriginCluster")
            .substringBefore("private fun updateControlLabel")
        for (method in listOf("onSuccess", "onError")) {
            val body = callbacks.substringAfter("override fun $method")
                .substringBefore("override fun ")
            assertTrue(body.indexOf("completeAdasStop.run()") < body.indexOf("runOnUiThread"))
            assertTrue(body.indexOf("cleanupCurrent(stopTicket)") < body.indexOf("restoreHomeIfRequested"))
            assertTrue(body.contains("finally {\n                            callerComplete.run()"))
        }
        assertTrue(callbacks.contains("valid = { ProjectionCommandBus.cleanupCurrent(stopTicket) }"))
    }
}
