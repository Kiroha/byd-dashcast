package com.byd.dashcast.ui.main

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SplitControllerLifecycleWiringTest {

    @Test
    fun `every asynchronous split callback checks host liveness`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/ui/main/SplitController.java").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/ui/main/SplitController.java").readText()
        val fullScreen = source.substringAfter("if (slot == 0 && mSecondDashboardPkg != null)")
            .substringBefore("private void relaunchPrimaryInSlot")
        val relaunch = source.substringAfter("private void relaunchPrimaryInSlot")
            .substringBefore("private void launchInSlot")
        val result = source.substringAfter("private void launchInSlot")
            .substringBefore("public void clearSplitState")

        assertTrue(fullScreen.split("mHost.runOnUiThread").drop(1)
            .all { it.take(180).contains("!mHost.isActivityAlive()") })
        assertTrue(relaunch.contains("if (!mHost.isActivityAlive()) return"))
        assertTrue(relaunch.split("isCurrentSecondDashboardReplacement").drop(1)
            .all { it.take(120).contains("generation") })
        assertTrue(result.contains("if (!mHost.isActivityAlive()) return"))
    }
}