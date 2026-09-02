package com.byd.dashcast.report

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BugWizardPendingDeliveryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun clear() {
        BugWizardPendingDelivery.clear(context)
    }

    @Test
    fun `completed report phase survives a durable round trip`() {
        val file = File.createTempFile(BugReportCapture.PREFIX, ".txt").apply {
            writeText("report")
        }

        assertTrue(BugWizardPendingDelivery.save(
            context, file, "redacted caption", BugWizardPendingDelivery.DELIVERING))
        assertEquals(
            BugWizardPendingDelivery.Record(
                file.absolutePath, "redacted caption", BugWizardPendingDelivery.DELIVERING),
            BugWizardPendingDelivery.load(context),
        )

        BugWizardPendingDelivery.clear(context)
        assertNull(BugWizardPendingDelivery.load(context))
        file.delete()
    }

    @Test
    fun `missing report cannot create a pending delivery`() {
        assertFalse(BugWizardPendingDelivery.save(
            context,
            File(context.filesDir, "missing.txt"),
            "caption",
            BugWizardPendingDelivery.BUNDLING,
        ))
    }

    @Test
    fun `legacy singleton remains resumable when a protected report is added`() {
        val wizard = File.createTempFile(BugReportCapture.PREFIX, ".txt").apply {
            writeText("wizard")
        }
        val legacy = File.createTempFile(BugReportCapture.PREFIX, ".txt").apply {
            writeText("legacy")
        }
        val prefs = context.getSharedPreferences(
            "dashcast_pending_bug_delivery", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("path", wizard.absolutePath)
            .putString("caption", "wizard caption")
            .putString("phase", BugWizardPendingDelivery.DELIVERING)
            .commit()

        assertTrue(BugWizardPendingDelivery.protect(context, legacy, "legacy caption"))

        assertEquals(wizard.absolutePath, BugWizardPendingDelivery.load(context)?.path)
        assertTrue(BugWizardPendingDelivery.protects(context, wizard))
        assertTrue(BugWizardPendingDelivery.protects(context, legacy))
        assertFalse(prefs.contains("path"))
        wizard.delete()
        legacy.delete()
    }

    @Test
    fun `multiple pending reports remain independently protected and clearable`() {
        val first = File.createTempFile(BugReportCapture.PREFIX, ".txt").apply {
            writeText("first")
        }
        val second = File.createTempFile(BugReportCapture.PREFIX, ".txt").apply {
            writeText("second")
        }
        BugWizardPendingDelivery.save(
            context, first, "first caption", BugWizardPendingDelivery.DELIVERING)
        BugWizardPendingDelivery.protect(context, second, "second caption")

        assertEquals(first.absolutePath, BugWizardPendingDelivery.load(context)?.path)
        assertTrue(BugWizardPendingDelivery.protects(context, first))
        assertTrue(BugWizardPendingDelivery.protects(context, second))

        BugWizardPendingDelivery.clear(context, second)

        assertEquals(first.absolutePath, BugWizardPendingDelivery.load(context)?.path)
        assertTrue(BugWizardPendingDelivery.protects(context, first))
        assertFalse(BugWizardPendingDelivery.protects(context, second))
        first.delete()
        second.delete()
    }
}