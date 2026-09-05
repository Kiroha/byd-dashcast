package com.byd.dashcast.infrastructure

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MultiTaskForceStopWiringTest {

    @Test
    fun `typed and legacy paths apply aggregate task decisions`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/infrastructure/AdbLocalClient.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/infrastructure/AdbLocalClient.kt").readText()
        val forceStop = source.substringAfter("fun forceStopApp")
            .substringBefore("// LOT 4")

        assertTrue(forceStop.contains("findTaskLocationsForEviction(packageName)"))
        assertTrue(forceStop.split("EvictionTaskSetPolicy.decide").size - 1 >= 3)
        assertTrue(forceStop.contains("removeTypedTasks(decision.taskIdsToRemove)"))
        assertTrue(forceStop.contains("removeLegacyTasks(dadb, context, decision.taskIdsToRemove)"))
        assertTrue(source.contains("LegacyTaskLocationParser.parseAll"))
        val legacyBeforeVerification = forceStop.substringAfter("legacyLocations: List<TaskLocation>")
            .substringBefore("verifyForceStopViaAdb")
        assertFalse(legacyBeforeVerification.contains("grep -F"))
        assertFalse(legacyBeforeVerification.contains("TaskRemover"))
    }
}