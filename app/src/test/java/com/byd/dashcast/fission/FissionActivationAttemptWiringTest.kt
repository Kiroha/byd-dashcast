package com.byd.dashcast.fission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FissionActivationAttemptWiringTest {

    @Test
    fun `manual and headless completions release only captured generation`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.kt").readText()
        val manual = source.substringAfter("fun activateLayoutManually")
            .substringBefore("fun launchFavoriteLayoutApps")
        val auto = source.substringAfter("private fun activateFavoriteLayout")
            .substringBefore("private fun isUsable")
        val failure = source.substringAfter("private fun markAutoStartFailed")
            .substringBefore("private fun precreateSlots")

        assertTrue(manual.contains("val activationToken = activation.token"))
        assertTrue(manual.contains("val ownedCompletion = sActivationGate.release(activationToken)"))
        assertTrue(manual.contains("if (ownedCompletion && error != null"))
        assertTrue(auto.contains("sActivationGate.release(mActivationGuardToken)"))
        assertTrue(failure.contains("sActivationGate.release(mActivationGuardToken)"))
        assertTrue(failure.contains("if (!ownedCompletion)"))
        assertTrue(failure.indexOf("if (!ownedCompletion)") <
            failure.indexOf("sAutoStartOrchestrator === this"))
        assertFalse(source.contains("sActivationInFlight.set(false)"))
    }
}