package com.byd.dashcast.infrastructure

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LegacyForceStopOutcomeWiringTest {

    @Test
    fun `legacy force stop emits outcome before every terminal callback`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/infrastructure/AdbLocalClient.java").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/infrastructure/AdbLocalClient.java").readText()
        val legacy = source.substringAfter("TaskLocation> legacyLocations")
            .substringBefore("// LOT 4")

        assertTrue(legacy.contains("LegacyTaskLocationParser.parseAll"))
        assertTrue(legacy.contains("EvictionTaskSetPolicy.decide"))
        assertTrue(legacy.contains("removeLegacyTasks"))
        for (terminal in listOf(
            "callback.onSuccess(\"force-stop OK (ADB, verified)\")",
            "callback.onError(verification.toString().trim())",
            "callback.onError(out)",
            "callback.onError(msg)",
        )) {
            val before = legacy.substringBefore(terminal).takeLast(400)
            assertTrue("$terminal lacks a preceding eviction outcome",
                before.contains("callback.onEvictionOutcome"))
        }
    }
}