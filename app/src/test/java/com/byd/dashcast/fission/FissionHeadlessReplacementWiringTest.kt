package com.byd.dashcast.fission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FissionHeadlessReplacementWiringTest {

    @Test
    fun `replacement starts only inside prior stop completion`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.java").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.java").readText()
        val replacement = source.substringAfter("private static void replaceHeadlessAfterStop")
            .substringBefore("public static void stopAutoOrchestrator")
        val stopCompletion = replacement.substringAfter("previous.stopAll(() -> {")

        assertFalse(replacement.substringBefore("previous.stopAll")
            .contains("next.initAsync(layout, true, false)") &&
            replacement.substringBefore("previous.stopAll").contains("previous != null"))
        assertTrue(stopCompletion.indexOf("sAutoStartOrchestrator = next") <
            stopCompletion.indexOf("next.initAsync(layout, true, false)"))
        assertTrue(stopCompletion.contains("sAutoStartOrchestrator != previous"))
        assertTrue(stopCompletion.contains("next.shutdown()"))
    }

    @Test
    fun `both replacement entry points use shared serialization and activation guard`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.java").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.java").readText()
        val auto = source.substringAfter("public static synchronized AutoStartResult")
            .substringBefore("private static ProjectionStateProvider")
        val manual = source.substringAfter("public static void launchFavoriteLayoutApps")
            .substringBefore("private static void replaceHeadlessAfterStop")

        assertTrue(auto.contains("replaceHeadlessAfterStop(orch, fav)"))
        assertTrue(manual.contains("sActivationGate.tryAcquire"))
        assertTrue(manual.contains("replaceHeadlessAfterStop(orch, fav)"))
    }
}