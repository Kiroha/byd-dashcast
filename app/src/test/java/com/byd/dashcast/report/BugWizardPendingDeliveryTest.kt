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
}