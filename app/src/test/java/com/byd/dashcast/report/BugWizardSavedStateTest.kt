package com.byd.dashcast.report

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewFlipper
import com.byd.dashcast.R
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BugWizardSavedStateTest {

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