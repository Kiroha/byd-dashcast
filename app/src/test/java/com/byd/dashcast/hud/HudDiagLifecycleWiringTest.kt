package com.byd.dashcast.hud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HudDiagLifecycleWiringTest {

    @Test
    fun `background HUD diagnostics cannot touch UI after destruction`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it,
                "app/src/main/java/com/byd/dashcast/hud/HudDiagActivity.kt").isFile }
        assertTrue("could not locate the repo root", root != null)
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/hud/HudDiagActivity.kt").readText()

        val destroy = source.substringAfter("override fun onDestroy()")
            .substringBefore("private fun isUiAlive")
        val postUi = source.substringAfter("private inline fun postUi")
            .substringBefore("private fun sh")
        assertTrue(destroy.indexOf("lifecycleGate.invalidate()") < destroy.indexOf("super.onDestroy()"))
        assertTrue(postUi.contains("!token.isValid || isFinishing || isDestroyed"))
        assertFalse(source.substringBefore("private inline fun postUi")
            .contains("runOnUiThread { ask"))
        for (method in listOf("askStep", "askStepNote", "askBench", "askSweepResult", "askBenchNote")) {
            val body = source.substringAfter("private fun $method").substringBefore("AlertDialog.Builder")
            assertTrue("$method lacks a lifecycle guard", body.contains("if (!isUiAlive()) return"))
        }
    }
}