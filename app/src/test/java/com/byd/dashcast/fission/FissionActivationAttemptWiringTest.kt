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
                "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.java").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.java").readText()
        val manual = source.substringAfter("public static synchronized void activateLayoutManually")
            .substringBefore("public static void launchFavoriteLayoutApps")
        val auto = source.substringAfter("private void activateFavoriteLayout")
            .substringBefore("private boolean isUsable")
        val failure = source.substringAfter("private void markAutoStartFailed")
            .substringBefore("private void precreateSlots")

        assertTrue(manual.contains("final long activationToken = activation.getToken()"))
        assertTrue(manual.contains("boolean ownedCompletion = sActivationGate.release(activationToken)"))
        assertTrue(manual.contains("if (ownedCompletion && error != null"))
        assertTrue(auto.contains("sActivationGate.release(mActivationGuardToken)"))
        assertTrue(failure.contains("sActivationGate.release(mActivationGuardToken)"))
        assertTrue(failure.contains("if (!ownedCompletion)"))
        assertTrue(failure.indexOf("if (!ownedCompletion)") <
            failure.indexOf("sAutoStartOrchestrator == this"))
        assertFalse(source.contains("sActivationInFlight.set(false)"))
    }
}