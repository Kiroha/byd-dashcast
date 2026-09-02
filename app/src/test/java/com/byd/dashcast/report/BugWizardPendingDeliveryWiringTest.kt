package com.byd.dashcast.report

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BugWizardPendingDeliveryWiringTest {

    @Test
    fun `post capture phases are durable until a safe terminal`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/report/BugWizardActivity.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/report/BugWizardActivity.kt").readText()
        val consent = source.substringAfter("private fun showScreenshotConsent")
            .substringBefore("private fun resumeScreenshotConsent")
        val bundle = source.substringAfter("private fun bundleShotsThenDeliver")
            .substringBefore("// ── Navigation")
        val delivery = source.substringAfter("private fun deliverReport")
            .substringBefore("private fun showScreenshotConsent")
        val failed = delivery.substringAfter("override fun onFailed")
            .substringBefore("override fun onAmbiguous")

        assertTrue(consent.contains("AWAITING_SCREENSHOT_CONSENT"))
        assertTrue(consent.contains("BugWizardPendingDelivery.BUNDLING"))
        assertTrue(bundle.indexOf("BugWizardPendingDelivery.BUNDLING") <
            bundle.indexOf("Thread({"))
        assertTrue(delivery.contains("BugWizardPendingDelivery.DELIVERING"))
        assertFalse("failure must leave the retry record",
            failed.contains("clearDurablePendingDelivery"))
        val share = source.substringAfter("private fun shareFallback")
            .substringBefore("private fun finishAmbiguousDelivery")
        assertTrue(share.indexOf("AppLogger.shareFile") <
            share.indexOf("if (chooserOpened) clearDurablePendingDelivery(file)"))
        assertFalse(share.substringBefore("AppLogger.shareFile")
            .contains("clearDurablePendingDelivery"))
    }
}