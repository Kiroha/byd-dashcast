package com.byd.dashcast.ui.main

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SplitControllerLifecycleWiringTest {

    @Test
    fun `every asynchronous split callback checks host liveness`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/ui/main/SplitController.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/ui/main/SplitController.kt").readText()
        val fullScreen = source.substringAfter("if (slot == 0 && mSecondDashboardPkg != null)")
            .substringBefore("private fun relaunchPrimaryInSlot")
        val relaunch = source.substringAfter("private fun relaunchPrimaryInSlot")
            .substringBefore("private fun launchInSlot")
        val result = source.substringAfter("private fun launchInSlot")
            .substringBefore("fun clearSplitState")

        assertTrue(fullScreen.split("mHost.runOnUiThread").drop(1)
            .all { it.take(180).contains("!mHost.isActivityAlive()") })
        assertTrue(relaunch.contains("if (!mHost.isActivityAlive()) return"))
        assertTrue(relaunch.split("isCurrentSecondDashboardReplacement").drop(1)
            .all { it.take(120).contains("generation") })
        assertTrue(result.contains("if (!mHost.isActivityAlive()) return"))
    }
}