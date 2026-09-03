package com.byd.dashcast.report

import android.os.Bundle

internal data class BugWizardSavedState(
    val category: Int,
    val hudArrowsAnswer: String,
    val hudNavApp: String,
    val activeNav: String,
    val navSeen: String,
    val appPackage: String,
    val appLabel: String,
    val selectedIssueIndex: Int,
    val details: String,
    val telegramHandle: String,
    val handleDraft: String,
    val currentStep: Int,
    val pendingGate: Int,
    val detectedPackage: String,
    val detectedLabel: String,
    val detectionDone: Boolean,
    val submissionToken: String,
    val pendingReportPath: String,
    val pendingReportCaption: String,
)

internal object BugWizardGate {
    const val NONE = 0
    const val HANDLE_REQUIRED = 1
    const val HANDLE_EDIT = 2
    const val HUD_ARROWS = 3
    const val HUD_NAV_APP = 4
    const val HUD_NO_ARROWS = 5
    const val HUD_NO_NOTIFICATION_ACCESS = 6
    const val HUD_NO_ROUTE = 7
    const val HUD_UNSUPPORTED_NAV = 8
    const val SHOTS_CONSENT = 9
    const val RESUME_BUNDLE = 10
    const val DELIVERY_RETRY = 11
}

internal object BugWizardStateStore {
    private const val PRESENT = "bug_wizard.present"
    private const val PREFIX = "bug_wizard."

    fun write(outState: Bundle, state: BugWizardSavedState) {
        outState.putBoolean(PRESENT, true)
        outState.putInt(PREFIX + "category", state.category)
        outState.putString(PREFIX + "hudArrows", state.hudArrowsAnswer)
        outState.putString(PREFIX + "hudNavApp", state.hudNavApp)
        outState.putString(PREFIX + "activeNav", state.activeNav)
        outState.putString(PREFIX + "navSeen", state.navSeen)
        outState.putString(PREFIX + "appPackage", state.appPackage)
        outState.putString(PREFIX + "appLabel", state.appLabel)
        outState.putInt(PREFIX + "issueIndex", state.selectedIssueIndex)
        outState.putString(PREFIX + "details", state.details)
        outState.putString(PREFIX + "telegramHandle", state.telegramHandle)
        outState.putString(PREFIX + "handleDraft", state.handleDraft)
        outState.putInt(PREFIX + "step", state.currentStep)
        outState.putInt(PREFIX + "gate", state.pendingGate)
        outState.putString(PREFIX + "detectedPackage", state.detectedPackage)
        outState.putString(PREFIX + "detectedLabel", state.detectedLabel)
        outState.putBoolean(PREFIX + "detectionDone", state.detectionDone)
        outState.putString(PREFIX + "submissionToken", state.submissionToken)
        outState.putString(PREFIX + "pendingReportPath", state.pendingReportPath)
        outState.putString(PREFIX + "pendingReportCaption", state.pendingReportCaption)
    }

    fun read(savedState: Bundle?): BugWizardSavedState? {
        if (savedState == null || !savedState.getBoolean(PRESENT, false)) return null
        return BugWizardSavedState(
            category = savedState.getInt(PREFIX + "category", -1),
            hudArrowsAnswer = savedState.getString(PREFIX + "hudArrows").orEmpty(),
            hudNavApp = savedState.getString(PREFIX + "hudNavApp").orEmpty(),
            activeNav = savedState.getString(PREFIX + "activeNav").orEmpty(),
            navSeen = savedState.getString(PREFIX + "navSeen").orEmpty(),
            appPackage = savedState.getString(PREFIX + "appPackage").orEmpty(),
            appLabel = savedState.getString(PREFIX + "appLabel").orEmpty(),
            selectedIssueIndex = savedState.getInt(PREFIX + "issueIndex", -1),
            details = savedState.getString(PREFIX + "details").orEmpty(),
            telegramHandle = savedState.getString(PREFIX + "telegramHandle").orEmpty(),
            handleDraft = savedState.getString(PREFIX + "handleDraft").orEmpty(),
            currentStep = savedState.getInt(PREFIX + "step", 0),
            pendingGate = savedState.getInt(PREFIX + "gate", BugWizardGate.NONE),
            detectedPackage = savedState.getString(PREFIX + "detectedPackage").orEmpty(),
            detectedLabel = savedState.getString(PREFIX + "detectedLabel").orEmpty(),
            detectionDone = savedState.getBoolean(PREFIX + "detectionDone", false),
            submissionToken = savedState.getString(PREFIX + "submissionToken").orEmpty(),
            pendingReportPath = savedState.getString(PREFIX + "pendingReportPath").orEmpty(),
            pendingReportCaption = savedState.getString(PREFIX + "pendingReportCaption").orEmpty(),
        )
    }
}