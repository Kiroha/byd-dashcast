package com.byd.dashcast.fission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FissionGlobalPurgeOrderingTest {

    @Test
    fun `free mode purge completes before activation gate is released`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.kt").readText()
        val staticStop = source.substringAfter("private fun stopAutoOrchestrator(")
            .substringBefore("// ── Public API")
        val instanceStop = source.substringAfter("private fun stopAll(purgeDaemonSlots: Boolean")
            .substringBefore("private fun forceStopAndWaitForResult")

        assertTrue(staticStop.indexOf("val teardownToken = sActivationGate.forceAcquire") <
            staticStop.indexOf("o.stopAllAndPurge(complete)"))
        val completion = staticStop.substringAfter("val complete = Runnable {")
            .substringBefore("};")
        assertTrue(completion.contains("sActivationGate.release(teardownToken)"))
        assertTrue(staticStop.contains("else o.stopAll(complete)"))
        assertTrue(staticStop.contains("o.stopAllAndPurge(complete)"))
        val terminal = instanceStop.substringAfter("FissionReleaseDebt.recordAll(unreleased)")
        assertTrue(terminal.indexOf("FissionClient.deactivateLayout(binder)") <
            terminal.indexOf("if (onComplete != null) onComplete.run()"))
    }

    @Test
    fun `layout manager has no detached post-teardown purge`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/fission/LayoutManagerActivity.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/fission/LayoutManagerActivity.kt").readText()

        assertTrue(source.contains("stopAutoOrchestratorAndPurge(this, null)"))
        assertFalse(source.contains("purgeDaemonSlotsAsync"))
    }
}