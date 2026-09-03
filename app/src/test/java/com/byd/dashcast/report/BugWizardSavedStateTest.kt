package com.byd.dashcast.report

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewFlipper
import com.byd.dashcast.R
import com.google.android.material.button.MaterialButton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BugWizardSavedStateTest {

    @After
    fun resetSubmissionGate() {
        BugWizardSubmissionGate.resetForTest()
        BugWizardPendingDelivery.clear(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `all wizard choices and pending gate survive a Bundle round trip`() {
        val expected = BugWizardSavedState(
            category = 0,
            hudArrowsAnswer = "yes",
            hudNavApp = "waze",
            activeNav = "telenav",
            navSeen = "parse-fail (12s ago)",
            appPackage = "com.waze",
            appLabel = "Waze",
            selectedIssueIndex = 3,
            details = "Only after the second turn",
            telegramHandle = "@driver",
            handleDraft = "@driver_edit",
            currentStep = 2,
            pendingGate = BugWizardGate.HUD_NO_ROUTE,
            detectedPackage = "com.google.android.apps.maps",
            detectedLabel = "Maps",
            detectionDone = true,
            submissionToken = "submission-1",
            pendingReportPath = "/tmp/report.txt",
            pendingReportCaption = "redacted caption",
        )
        val bundle = Bundle()

        BugWizardStateStore.write(bundle, expected)

        assertEquals(expected, BugWizardStateStore.read(bundle))
    }

    @Test
    fun `recreated activity restores issue page selection and details`() {
        val bundle = Bundle()
        BugWizardStateStore.write(
            bundle,
            BugWizardSavedState(
                category = 1,
                hudArrowsAnswer = "",
                hudNavApp = "",
                activeNav = "",
                navSeen = "",
                appPackage = "com.example.maps",
                appLabel = "Maps",
                selectedIssueIndex = 2,
                details = "Happens after reconnect",
                telegramHandle = "@driver",
                handleDraft = "",
                currentStep = 2,
                pendingGate = BugWizardGate.NONE,
                detectedPackage = "com.example.maps",
                detectedLabel = "Maps",
                detectionDone = true,
                submissionToken = "",
                pendingReportPath = "",
                pendingReportCaption = "",
            ),
        )

        val controller = Robolectric.buildActivity(BugWizardActivity::class.java).create(bundle)
        val activity = controller.get()
        val flipper = activity.findViewById<ViewFlipper>(R.id.bug_wizard_flipper)
        val issues = activity.findViewById<LinearLayout>(R.id.ll_wizard_issues)
        val children = (0 until issues.childCount).map(issues::getChildAt)
        val issueButtons = children.filterIsInstance<MaterialButton>()
            .take(activity.resources.getStringArray(R.array.bug_issues_mirror).size)
        val details = children.filterIsInstance<EditText>().single()

        assertEquals(2, flipper.displayedChild)
        assertTrue(issueButtons[2].isChecked)
        assertEquals("Happens after reconnect", details.text.toString())
        assertTrue(children.filterIsInstance<MaterialButton>().last().isEnabled)

        controller.destroy()
    }

    @Test
    fun `recreated activity resumes the pending HUD gate`() {
        val bundle = Bundle()
        BugWizardStateStore.write(
            bundle,
            BugWizardSavedState(
                category = 0,
                hudArrowsAnswer = "yes",
                hudNavApp = "waze",
                activeNav = "",
                navSeen = "no",
                appPackage = "",
                appLabel = "",
                selectedIssueIndex = -1,
                details = "",
                telegramHandle = "@driver",
                handleDraft = "",
                currentStep = 0,
                pendingGate = BugWizardGate.HUD_NO_ROUTE,
                detectedPackage = "",
                detectedLabel = "",
                detectionDone = true,
                submissionToken = "",
                pendingReportPath = "",
                pendingReportCaption = "",
            ),
        )

        val controller = Robolectric.buildActivity(BugWizardActivity::class.java).create(bundle)
        val activity = controller.get()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()

        assertTrue(dialog.isShowing)
        assertEquals(
            activity.getString(R.string.bug_hud_no_route_msg),
            dialog.findViewById<TextView>(android.R.id.message).text.toString(),
        )

        controller.destroy()
    }

    @Test
    fun `recreated activity cannot resubmit while prior capture owns the token`() {
        val token = BugWizardSubmissionGate.claim()!!
        BugWizardSubmissionGate.setBackgroundWork(token, true)
        val bundle = issuePageState(token)

        val controller = Robolectric.buildActivity(BugWizardActivity::class.java).create(bundle)
        val activity = controller.get()
        val issues = activity.findViewById<LinearLayout>(R.id.ll_wizard_issues)
        val send = (0 until issues.childCount).map(issues::getChildAt)
            .filterIsInstance<MaterialButton>().last()

        assertTrue(!send.isEnabled)
        assertTrue(activity.findViewById<View>(R.id.btn_wizard_cancel).isEnabled)
        assertNull(BugWizardSubmissionGate.claim())

        controller.destroy()
    }

    @Test
    fun `saved token does not lock wizard after process ownership is gone`() {
        val bundle = issuePageState("token-from-dead-process")

        val controller = Robolectric.buildActivity(BugWizardActivity::class.java).create(bundle)
        val activity = controller.get()
        val issues = activity.findViewById<LinearLayout>(R.id.ll_wizard_issues)
        val send = (0 until issues.childCount).map(issues::getChildAt)
            .filterIsInstance<MaterialButton>().last()

        assertTrue(send.isEnabled)

        controller.destroy()
    }

    @Test
    fun `recreation resumes screenshot consent for the completed report`() {
        val report = File.createTempFile(BugReportCapture.PREFIX, ".txt").apply {
            writeText("completed report")
        }
        val token = BugWizardSubmissionGate.claim()!!
        val bundle = issuePageState(
            token,
            pendingGate = BugWizardGate.SHOTS_CONSENT,
            pendingReportPath = report.absolutePath,
            pendingReportCaption = "redacted caption",
        )

        val controller = Robolectric.buildActivity(BugWizardActivity::class.java).create(bundle)
        val activity = controller.get()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()

        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(R.string.bug_shots_consent_msg),
            dialog.findViewById<TextView>(android.R.id.message).text.toString())
        assertTrue(BugWizardSubmissionGate.isActive(token))

        controller.destroy()
        report.delete()
    }

    @Test
    fun `process recreation reclaims completed report screenshot consent`() {
        val report = File.createTempFile(BugReportCapture.PREFIX, ".txt").apply {
            writeText("completed report")
        }
        val bundle = issuePageState(
            "token-from-dead-process",
            pendingGate = BugWizardGate.SHOTS_CONSENT,
            pendingReportPath = report.absolutePath,
            pendingReportCaption = "redacted caption",
        )

        val controller = Robolectric.buildActivity(BugWizardActivity::class.java).create(bundle)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val reclaimed = BugWizardSubmissionGate.activeToken()

        assertTrue(dialog.isShowing)
        assertTrue(reclaimed != null && reclaimed != "token-from-dead-process")

        controller.destroy()
        report.delete()
    }

    @Test
    fun `independent wizard waits without adopting or closing with another submission`() {
        val otherToken = BugWizardSubmissionGate.claim()!!
        val controller = Robolectric.buildActivity(BugWizardActivity::class.java)
            .create(issuePageState(""))
            .start().resume().visible()
        val activity = controller.get()
        val issues = activity.findViewById<LinearLayout>(R.id.ll_wizard_issues)
        val send = (0 until issues.childCount).map(issues::getChildAt)
            .filterIsInstance<MaterialButton>().last()

        send.performClick()

        assertEquals(otherToken, BugWizardSubmissionGate.activeToken())
        assertTrue(!send.isEnabled)
        assertTrue(!activity.isFinishing)

        BugWizardSubmissionGate.release(otherToken)
        Shadows.shadowOf(activity.mainLooper).idleFor(600, TimeUnit.MILLISECONDS)

        assertTrue(send.isEnabled)
        assertTrue(!activity.isFinishing)

        controller.destroy()
    }

    @Test
    fun `process death during upload restores explicit retry without automatic send`() {
        val report = File.createTempFile(BugReportCapture.PREFIX, ".txt").apply {
            writeText("completed report")
        }
        assertTrue(BugWizardPendingDelivery.save(
            RuntimeEnvironment.getApplication(),
            report,
            "redacted caption",
            BugWizardPendingDelivery.DELIVERING,
        ))

        val controller = Robolectric.buildActivity(BugWizardActivity::class.java)
            .create(issuePageState(""))
            .start().resume().visible()
        val activity = controller.get()
        val issues = activity.findViewById<LinearLayout>(R.id.ll_wizard_issues)
        val send = (0 until issues.childCount).map(issues::getChildAt)
            .filterIsInstance<MaterialButton>().last()
        val status = activity.findViewById<TextView>(R.id.tv_wizard_status)

        assertTrue(send.isEnabled)
        assertTrue(!activity.findViewById<MaterialButton>(R.id.btn_wizard_back).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.btn_wizard_cancel).isEnabled)
        assertEquals(activity.getString(R.string.bug_kept_locally_fmt, report.name),
            status.text.toString())
        assertNull(BugWizardSubmissionGate.activeToken())

        controller.destroy()
        report.delete()
    }

    @Test
    fun `fresh wizard can retry durable delivery without an Activity bundle`() {
        val report = File.createTempFile(BugReportCapture.PREFIX, ".txt").apply {
            writeText("completed report")
        }
        BugWizardPendingDelivery.save(
            RuntimeEnvironment.getApplication(),
            report,
            "redacted caption",
            BugWizardPendingDelivery.DELIVERING,
        )

        val controller = Robolectric.buildActivity(BugWizardActivity::class.java)
            .create().start().resume().visible()
        val activity = controller.get()
        val issues = activity.findViewById<LinearLayout>(R.id.ll_wizard_issues)
        val buttons = (0 until issues.childCount).map(issues::getChildAt)
            .filterIsInstance<MaterialButton>()

        assertEquals(2, activity.findViewById<ViewFlipper>(R.id.bug_wizard_flipper).displayedChild)
        assertEquals(1, buttons.size)
        assertTrue(buttons.single().isEnabled)
        assertNull(BugWizardSubmissionGate.activeToken())

        controller.destroy()
        report.delete()
    }

    private fun issuePageState(
        token: String,
        pendingGate: Int = BugWizardGate.NONE,
        pendingReportPath: String = "",
        pendingReportCaption: String = "",
    ): Bundle = Bundle().also { bundle ->
        BugWizardStateStore.write(
            bundle,
            BugWizardSavedState(
                category = 1,
                hudArrowsAnswer = "",
                hudNavApp = "",
                activeNav = "",
                navSeen = "",
                appPackage = "com.example.maps",
                appLabel = "Maps",
                selectedIssueIndex = 2,
                details = "Happens after reconnect",
                telegramHandle = "@driver",
                handleDraft = "",
                currentStep = 2,
                pendingGate = pendingGate,
                detectedPackage = "com.example.maps",
                detectedLabel = "Maps",
                detectionDone = true,
                submissionToken = token,
                pendingReportPath = pendingReportPath,
                pendingReportCaption = pendingReportCaption,
            ),
        )
    }

    @Test
    fun `activity saves live drafts and reconstructs the selected page and gate`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/report/BugWizardActivity.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/report/BugWizardActivity.kt").readText()
        val save = source.substringAfter("override fun onSaveInstanceState")
            .substringBefore("// ── Step 0")

        assertTrue(save.contains("mDetailsField?.text"))
        assertTrue(save.contains("mHandleField?.text"))
        assertTrue(save.contains("mCurrentStep"))
        assertTrue(save.contains("mPendingGate"))
        assertTrue(source.contains("if (mCurrentStep == 2)"))
        assertTrue(source.contains("onIssuePicked(issue, issueIndex, mIssueButtons[issueIndex])"))
        assertTrue(source.contains("resumePendingGate()"))
    }
}