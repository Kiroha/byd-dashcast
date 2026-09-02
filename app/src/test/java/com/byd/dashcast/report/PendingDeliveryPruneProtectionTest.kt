package com.byd.dashcast.report

import android.content.Context
import com.byd.dashcast.util.AppLogger
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PendingDeliveryPruneProtectionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clean() {
        BugWizardPendingDelivery.clear(context)
        context.getExternalFilesDir(null)?.listFiles()?.forEach {
            if (it.isFile && it.name.startsWith(BugReportCapture.PREFIX)) it.delete()
        }
        ReportStore.dir(context).deleteRecursively()
    }

    @After
    fun clear() {
        BugWizardPendingDelivery.clear(context)
        ReportStore.dir(context).deleteRecursively()
    }

    @Test
    fun `concurrent loose pending reports survive prefix rotation`() {
        val root = context.getExternalFilesDir(null)!!
        val pending = File(root, BugReportCapture.PREFIX + "pending.txt").apply {
            writeText("pending")
            setLastModified(1)
        }
        val legacyPending = File(root, BugReportCapture.PREFIX + "legacy_pending.txt").apply {
            writeText("legacy pending")
            setLastModified(2)
        }
        repeat(4) { index ->
            File(root, BugReportCapture.PREFIX + "new_$index.txt").apply {
                writeText("new")
                setLastModified(10_000L + index)
            }
        }
        BugWizardPendingDelivery.save(
            context, pending, "caption", BugWizardPendingDelivery.DELIVERING)
        BugWizardPendingDelivery.protect(context, legacyPending, "legacy caption")

        AppLogger.pruneOldFiles(context, 1)

        assertTrue(pending.exists())
        assertTrue(legacyPending.exists())
    }

    @Test
    fun `concurrent pending bundles survive age size and count pruning`() {
        val pending = File(ReportStore.dir(context), "pending.zip").apply {
            writeText("pending")
            setLastModified(1)
        }
        val legacyPending = File(ReportStore.dir(context), "legacy_pending.zip").apply {
            writeText("legacy pending")
            setLastModified(2)
        }
        repeat(ReportStore.KEEP_FILES + 2) { index ->
            File(ReportStore.dir(context), "new_$index.zip").apply {
                writeText("new")
                setLastModified(System.currentTimeMillis() - index * 1000L)
            }
        }
        BugWizardPendingDelivery.save(
            context, pending, "caption", BugWizardPendingDelivery.DELIVERING)
        BugWizardPendingDelivery.protect(context, legacyPending, "legacy caption")

        ReportStore.prune(context)

        assertTrue(pending.exists())
        assertTrue(legacyPending.exists())
    }
}