package com.byd.dashcast.report

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BugReportActivityLifecycleWiringTest {

    @Test
    fun `all delayed legacy report callbacks stop UI work after destruction`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/report/BugReportActivity.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/report/BugReportActivity.kt").readText()
        val capture = source.substringAfter("BugReportCapture.capture")
            .substringBefore("private fun deliverHeadlessly")
        val ready = capture.substringAfter("override fun onReady")
            .substringBefore("override fun onError(message")
        val error = capture.substringAfter("override fun onError(message")

        assertTrue(ready.contains("if (!isUiAlive())"))
        assertTrue(ready.contains("deliverHeadlessly(file, caption)"))
        assertTrue(error.indexOf("if (!isUiAlive())") < error.indexOf("tvStatus.text"))

        for (terminal in listOf("onSent", "onFailed", "onAmbiguous")) {
            val body = ready.substringAfter("override fun $terminal")
                .substringBefore("override fun", missingDelimiterValue = ready)
            assertTrue("$terminal lacks a lifecycle guard", body.contains("isUiAlive()"))
        }
    }

    @Test
    fun `headless continuation uses application context and never opens UI`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/report/BugReportActivity.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/report/BugReportActivity.kt").readText()
        val headless = source.substringAfter("private fun deliverHeadlessly")
            .substringBefore("private fun isUiAlive")

        assertTrue(headless.contains("TelegramBugReporter.send(applicationContext"))
        assertFalse(headless.contains("Toast.makeText"))
        assertFalse(headless.contains("shareFallback"))
        assertFalse(headless.contains("tvStatus"))
    }
}