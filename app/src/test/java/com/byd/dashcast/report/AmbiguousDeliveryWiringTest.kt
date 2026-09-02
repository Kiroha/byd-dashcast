package com.byd.dashcast.report

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AmbiguousDeliveryWiringTest {

    @Test
    fun `wizard ambiguous outcome keeps file without share fallback`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/report/BugWizardActivity.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/report/BugWizardActivity.kt").readText()
        val delivery = source.substringAfter("private fun deliverReport")
            .substringBefore("private fun showScreenshotConsent")
        val ambiguous = delivery.substringAfter("override fun onAmbiguous")

        assertTrue(ambiguous.contains("finishAmbiguousDelivery(file)"))
        assertFalse(ambiguous.substringBefore("})").contains("shareFallback"))
        assertFalse(ambiguous.substringBefore("})").contains("clearDurablePendingDelivery"))
        val finish = source.substringAfter("private fun finishAmbiguousDelivery")
            .substringBefore("companion object")
        assertTrue(finish.contains("bug_kept_locally_fmt"))
        assertFalse(finish.contains("AppLogger.shareFile"))
        assertFalse(finish.contains("clearDurablePendingDelivery"))
    }

    @Test
    fun `headless wizard ambiguity also retains durable retry protection`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/report/BugWizardActivity.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/report/BugWizardActivity.kt").readText()

        for (message in listOf(
            "headless report delivery uncertain",
            "headless bundle delivery uncertain",
        )) {
            val callback = source.substringBefore(message).substringAfterLast(
                "override fun onAmbiguous(message: String)")
            assertFalse("$message clears the retry record",
                callback.contains("clearDurablePendingDelivery"))
            assertTrue("$message must release only transient submission ownership",
                callback.contains("BugWizardSubmissionGate.release(token)"))
        }
    }

    @Test
    fun `every Telegram callback implements ambiguous terminal`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/java/com/byd/dashcast").isDirectory }
        val files = listOf(
            "app/src/main/java/com/byd/dashcast/hud/HudDiagActivity.kt",
            "app/src/main/java/com/byd/dashcast/hud/HudRawCaptureActivity.kt",
            "app/src/main/java/com/byd/dashcast/report/BugReportActivity.kt",
            "app/src/main/java/com/byd/dashcast/report/BugWizardActivity.kt",
            "app/src/main/java/com/byd/dashcast/ui/diag/DiagActivity.kt",
        )
        for (path in files) {
            val source = File(root, path).readText()
            val callbacks = source.split("object : TelegramBugReporter.Callback").size - 1
            val ambiguous = source.split("override fun onAmbiguous").size - 1
            assertTrue("$path has no Telegram callback", callbacks > 0)
            assertTrue("$path has a callback flattening ambiguity",
                ambiguous >= callbacks)
        }
    }
}