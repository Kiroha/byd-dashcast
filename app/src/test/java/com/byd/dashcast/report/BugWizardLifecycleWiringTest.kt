package com.byd.dashcast.report

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BugWizardLifecycleWiringTest {

    @Test
    fun `every delayed result rejects UI work after wizard destruction`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/report/BugWizardActivity.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/report/BugWizardActivity.kt").readText()

        val captureError = source.substringAfter("override fun onError(message: String, partial: File?)")
            .substringBefore("})\n    }")
        assertTrue(captureError.contains("if (!isUiAlive())"))
        assertTrue(captureError.indexOf("if (!isUiAlive())") < captureError.indexOf("mTvStatus.text"))

        val delivery = source.substringAfter("private fun deliverReport")
            .substringBefore("private fun bundleShotsThenDeliver")
        val sent = delivery.substringAfter("override fun onSent()")
            .substringBefore("override fun onFailed")
        val failed = delivery.substringAfter("override fun onFailed(message: String)")
        assertTrue(sent.contains("if (!isUiAlive())"))
        assertTrue(failed.contains("if (!isUiAlive())"))

        val detection = source.substringAfter("private fun detectClusterApp")
            .substringBefore("private fun onDetectionResult")
        assertTrue(detection.contains("if (isUiAlive()) onDetectionResult"))
        assertTrue(source.contains("if (isUiAlive()) mActiveNav = hit"))
    }
}