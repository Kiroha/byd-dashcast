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
                "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.kt").readText()
        val replacement = source.substringAfter("private fun replaceHeadlessAfterStop")
            .substringBefore("fun stopAutoOrchestrator")
        val stopCompletion = replacement.substringAfter("previous.stopAll {")

        assertFalse(replacement.substringBefore("previous.stopAll")
            .contains("next.initAsync(layout, true, false)") &&
            replacement.substringBefore("previous.stopAll").contains("previous != null"))
        assertTrue(stopCompletion.indexOf("sAutoStartOrchestrator = next") <
            stopCompletion.indexOf("next.initAsync(layout, true, false)"))
        assertTrue(stopCompletion.contains("sAutoStartOrchestrator !== previous"))
        assertTrue(stopCompletion.contains("next.shutdown()"))
    }

    @Test
    fun `both replacement entry points use shared serialization and activation guard`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.kt").readText()
        val auto = source.substringAfter("fun maybeAutoStartOnAppLaunch")
            .substringBefore("private fun headlessProjectionState")
        val manual = source.substringAfter("fun launchFavoriteLayoutApps")
            .substringBefore("private fun replaceHeadlessAfterStop")

        assertTrue(auto.contains("replaceHeadlessAfterStop(orch, fav)"))
        assertTrue(manual.contains("sActivationGate.tryAcquire"))
        assertTrue(manual.contains("replaceHeadlessAfterStop(orch, fav)"))
    }
}