package com.byd.dashcast.ui.diag

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.widget.Button
import android.widget.LinearLayout
import com.byd.dashcast.R
import com.byd.dashcast.cluster.display.ProjectionCommandBus
import com.byd.dashcast.cluster.display.ProjectionCommandSequencer
import com.byd.dashcast.clusterlive.ClusterNativeProjectionDebt
import com.byd.dashcast.clusterlive.ClusterThemeDiagnostics
import com.byd.dashcast.clusterlive.ClusterThemePolicy
import com.byd.dashcast.clusterlive.ClusterThemeProjectionDebt
import com.byd.dashcast.clusterlive.ClusterThemeVariant
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.proxy.ClusterThemeSession
import com.byd.dashcast.proxy.ProxyClient
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Guided page for the « map + BYD instruments » full-screen theme, plus the reserved Restore lane.
 * Restore stays independent of both the command lane and Diag's global busy gate.
 */
internal class ClusterThemeLiveControls(private val activity: Activity, header: LinearLayout, controls: LinearLayout,
    private val claimDiag: () -> Boolean, private val releaseDiag: () -> Unit,
    private val otherBusy: () -> Boolean, private val transcript: (String) -> Unit,
    reportShortcut: (LinearLayout) -> Unit = {},
    private val nativeStatus: (String, ClusterThemeSession) -> Unit = { _, _ -> },
    private val prompts: ClusterObservationPrompts = ClusterObservationPrompts(activity),
    private val nativeStatusUnavailable: () -> Unit = {},
    private val openClusterPage: () -> Unit = {}, dashboardContainer: LinearLayout? = null,
    private val restartConnection: (android.content.Context) -> com.byd.dashcast.infrastructure.ClusterRestartConnection =
        com.byd.dashcast.infrastructure.AdbLocalClient::clusterRestartConnection,
    restartBoot: () -> String = com.byd.dashcast.clusterlive.ClusterBootIdentity::read) {
    companion object {
        /** The only profile this page loads. Sessions left by older profiles are finished through Restore. */
        private val THEME = ClusterThemeVariant.FULLSCREEN_INSTRUMENTS_21F7735C
        private val REJECTED_GEOMETRY = setOf("UNAVAILABLE", "AMBIGUOUS", "OWNER_REJECTED", "STATE_REJECTED", "SIZE_REJECTED")
        private val ROUND = Regex("[0-9a-f]{32}")
        private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "cluster-test-ui").apply { isDaemon = true } }
        private val recovery = Executors.newSingleThreadExecutor { r -> Thread(r, "cluster-test-restore-ui").apply { isDaemon = true } }
        private val busy = AtomicBoolean(false)
        private val recoveryBusy = AtomicBoolean(false)
        private val diagClaimed = AtomicBoolean(false)
        private val lease = AtomicLong(0)
        private val abortBeforeSend = AtomicBoolean(false)
        private val reservationLock = Any()
        private var reservation: ProjectionCommandSequencer.Session? = null
        private var reservationFilename = ""
        private var reservationDebt = false
        private var dashboardFission: com.byd.dashcast.fission.FissionOrchestrator? = null
        private var dashboardUi = java.lang.ref.WeakReference<ClusterThemeLiveControls>(null)
        private var dashboardPackage = ""
        private var dashboardLabel = ""
        @Volatile private var dashboardSlotsUnsettled = false
        private var fullAppAdas: com.byd.dashcast.cluster.FullAppProjectionAdas? = null
    }
    private val handler = Handler(Looper.getMainLooper())
    private val app = activity.applicationContext
    @Volatile private var closed = false
    @Volatile private var session: ClusterThemeSession? = null
    private var confirmation: AlertDialog? = null
    private var recoveryGuidance: AlertDialog? = null
    private var recoveryGuidanceKey = ""
    private var recoveryGuidanceCurrent: (() -> Boolean)? = null
    private val shownRecoveryGuidance = linkedSetOf<String>()
    private var lastSummary = ""
    private val restartIntent = com.byd.dashcast.clusterlive.ClusterRestartIntent(app, restartBoot)
    private var restartRound = ""
    private var restartSession: ClusterThemeSession? = null
    @Volatile private var restartPreparing = false
    private var restartWizardRequested = false
    private val restartSettlementPending = AtomicBoolean(false)
    @Volatile private var starting = false
    private enum class Step { READ, UPDATE, NONE, LOAD, WAIT, CHOOSE, START, CLOSED, PROJECTING, CONFIRM, STOP, RECOVER, VERIFY }
    @Volatile private var active = false
    @Volatile private var activation = 0L
    private val dashboardCommandGeneration = AtomicLong(0)
    private class DashboardOpenTicket(val activation: Long, val filename: String,
        val reservation: ProjectionCommandSequencer.Session, val pinned: ClusterThemeSession,
        val variant: ClusterThemeVariant) {
        val dispatched = AtomicBoolean(false)
    }
    @Volatile private var dashboardTicket: DashboardOpenTicket? = null
    @Volatile private var dashboardFilename = ""
    @Volatile private var dashboardLaunchStateReady = false
    @Volatile private var statusPending = false
    private val observationPending = AtomicBoolean(false)
    private var displayed: JSONObject? = null
    private var displayedSession: ClusterThemeSession? = null
    private var openReady = false
    private var observeReady = false
    private var factoryApplyAvailable = false
    private val restoreButton = Button(activity)
    private val page = FullscreenThemeView(activity)
    private val poll = object : Runnable {
        override fun run() {
            if (closed || !active) return
            val s = session
            if (s != null) submit("status", automatic = true) { s.status() }
            handler.postDelayed(this, 1500)
        }
    }
    init {
        dashboardUi = java.lang.ref.WeakReference(this)
        (dashboardContainer ?: controls).addView(page.root)
        reportShortcut(page.footer)
        updateWorkflow(null)
        val density = activity.resources.displayMetrics.density
        // The fixed header keeps Restore reachable from every Diag page.
        header.addView(restoreButton.apply {
            text = activity.getString(R.string.diag_cluster_rcc_restore); contentDescription = text
            isAllCaps = false; textSize = 17f; minHeight = (56 * density).toInt(); stateListAnimator = null
            setTypeface(typeface, Typeface.BOLD); setTextColor(activity.getColor(R.color.md_error))
            background = GradientDrawable().apply {
                cornerRadius = 14 * density; setColor(activity.getColor(R.color.md_surface_container_low))
                setStroke((2 * density).toInt(), activity.getColor(R.color.md_error))
            }
            setOnClickListener {
                confirmation?.dismiss(); confirmation = null
                recoveryGuidance?.dismiss(); recoveryGuidance = null; recoveryGuidanceCurrent = null
                abortBeforeSend.set(true)
                restartWizardRequested = true
                dashboardTicket = null; dashboardCommandGeneration.incrementAndGet()
                ClusterThemeDiagnostics.local(app, "restore-button", "LOCAL", "Opening Cluster page and reading current pinned daemon status before choosing recovery; no OEM request yet.")
                openClusterPage()
                stopDashboardSlots { requestRecoveryAction() }
            }
        }, 0)
    }
    private fun string(id: Int, vararg args: Any): String = activity.getString(id, *args)
    private fun strings(vararg ids: Int) = ids.map { activity.getString(it) }
    private fun readStatus() = submit("status") { ClusterThemeSession.pin(app).also { session = it }.status() }
    private fun requestStart(variant: ClusterThemeVariant) {
        if (variant.isRecoveryOnly) { notice(R.string.fs_recover_other); return }
        if (ClusterNativeProjectionDebt.blocked(app) || displayed?.optJSONObject("nativeMode")?.optBoolean("debt") == true) {
            showNativeBlock()
        } else if (ClusterThemeProjectionDebt.blocked(app)) {
            notice(R.string.fs_recover_message); confirmIviRestore()
        } else confirm(variant)
    }
    private fun showNativeBlock() {
        ClusterThemeDiagnostics.local(app, "rcc-start-blocked", "REFUSED", "Native mode recovery/visual normal confirmation remains pending; no RCC load submitted. Return 18 is manual; answering the observation never starts RCC.")
        show(string(R.string.diag_cluster_rcc_blocked_message))
        prompts.explainBlocked()
    }
    private fun confirm(variant: ClusterThemeVariant) {
        confirmation?.dismiss()
        val dialog = GuidedDialog(activity).apply {
            title = string(R.string.fs_load_dialog_title)
            preview = ClusterPreviewView.Mode.STOCK
            steps = strings(R.string.fs_load_step_copy, R.string.fs_load_step_same, R.string.fs_load_step_return)
            expected = string(R.string.fs_load_expected)
            checks = strings(R.string.fs_check_parked, R.string.fs_check_cluster)
            positive = string(R.string.fs_load_confirm)
        }.create { popup -> popup.dismiss(); confirmation = null; begin(variant) }
        confirmation = dialog
        dialog.show()
    }
    private fun chooseDashboardApp() {
        val expectedActivation = activation
        work.execute {
            val pm = app.packageManager
            val homes = pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }.toSet()
            val apps = pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != app.packageName && com.byd.dashcast.cluster.ProjectionSafetyPolicy.isAllowed(it.activityInfo.packageName, it.activityInfo.packageName in homes) }
                .distinctBy { it.activityInfo.packageName }
                .map { Triple(it.activityInfo.packageName, it.loadLabel(pm).toString(), runCatching { it.loadIcon(pm) }.getOrNull()) }
                .sortedBy { it.second.lowercase(Locale.getDefault()) }
            handler.post {
                if (closed || !active || activation != expectedActivation) return@post
                confirmation?.dismiss()
                val dialog = GuidedDialog(activity).apply {
                    title = string(R.string.fs_choose_title)
                    lead = string(if (apps.isEmpty()) R.string.fs_choose_empty else R.string.fs_app_message)
                    choices = apps.map { (pkg, label, icon) ->
                        GuidedDialog.Choice(label, icon = icon) {
                            dashboardPackage = pkg; dashboardLabel = label
                            displayed?.let(::renderDashboard)
                        }
                    }
                }.create()
                confirmation = dialog
                dialog.show()
            }
        }
    }
    /** The page serves the « map + BYD instruments » profile only; any other owned session is finished through Restore. */
    private fun step(j: JSONObject?): Pair<String, Step> {
        if (restartPreparing || restartRound.isNotEmpty()) return string(R.string.cluster_restart_checking) to Step.WAIT
        if (starting) return string(R.string.fs_loading_message) to Step.WAIT
        if (j == null) return string(R.string.fs_check_message) to Step.READ
        val variant = dashboardVariant(j)
        val own = j.optBoolean("rccDebt")
        val dashboard = j.optJSONObject("dashboard")
        val stage = dashboard?.optString("stage").orEmpty()
        val geometry = dashboard?.optJSONObject("geometry")?.optString("verdict").orEmpty()
        val phase = j.optString("phase")
        val smallPanel = ClusterPrefs.isSmallClusterPanelLatched(app)
        return when {
            j.optBoolean("restartDispatchPending") || restartIntent.pending() -> string(R.string.fs_recover_unknown) to Step.RECOVER
            j.optBoolean("recoveryPending") -> string(R.string.fs_wait_message) to Step.WAIT
            j.optJSONObject("retirement")?.optBoolean("moved") == true -> string(R.string.fs_recover_restart) to Step.RECOVER
            // The factory selection is back on the daemon side; only the user's look at the cluster can close the session.
            j.optBoolean("defaultQueried") && !j.optBoolean("oemObservedByUser") && j.optString("state") in setOf("DEFAULT_QUERIED", "QUARANTINE") &&
                ClusterThemePolicy.ownedName(j.optString("filename")) -> string(R.string.fs_oem_question_message) to Step.VERIFY
            own && variant != THEME -> string(R.string.fs_recover_other) to Step.RECOVER
            own && (j.optBoolean("uncertain") || stage == "UNKNOWN" || j.optBoolean("factoryApplyPending")) -> string(R.string.fs_recover_message) to Step.RECOVER
            own && (geometry == "SMALL" || smallPanel) -> string(R.string.fs_small_message) to Step.RECOVER
            own && geometry in REJECTED_GEOMETRY -> string(R.string.fs_recover_display) to Step.RECOVER
            own && dashboard?.optBoolean("bootstrapStarted") == true && !j.optBoolean("mutated") &&
                j.optString("state") in setOf("QUARANTINE", "DEFAULT_QUERIED") -> string(R.string.fs_recover_message) to Step.RECOVER
            own && stage == "OPEN_RECEIVED" && j.optBoolean("themeObservedByUser") -> string(R.string.fs_done_message) to Step.STOP
            own && stage == "OPEN_RECEIVED" ->
                if (observeReady) string(R.string.fs_observe_message) to Step.CONFIRM else string(R.string.fs_opening_message) to Step.PROJECTING
            own && stage == "OPENING" -> string(R.string.fs_opening_message) to Step.PROJECTING
            own && stage == "CLOSING" -> string(R.string.fs_closing_message) to Step.WAIT
            openReady && dashboardPackage.isEmpty() -> string(R.string.fs_app_message) to Step.CHOOSE
            openReady && stage == "CLOSED_RECEIVED" -> string(R.string.fs_closed_message, dashboardLabel) to Step.CLOSED
            openReady -> string(R.string.fs_start_message, dashboardLabel) to Step.START
            own && phase.startsWith("dashboard-bootstrap-") -> string(R.string.fs_bootstrap_message) to Step.WAIT
            own && phase == "dashboard-display-wait" -> string(R.string.fs_display_message) to Step.WAIT
            own -> string(R.string.fs_loading_message) to Step.WAIT
            !ProxyClient.supportsProtocol(THEME.minimumProtocol) -> string(R.string.fs_protocol_message, THEME.minimumProtocol) to Step.UPDATE
            smallPanel -> string(R.string.fs_small_message) to Step.NONE
            else -> string(R.string.fs_load_message) to Step.LOAD
        }
    }
    private fun updateWorkflow(j: JSONObject?) {
        val (message, step) = step(j)
        val own = j?.optBoolean("rccDebt") == true
        val tone = FullscreenThemeView.Tone.NEUTRAL
        val unknown = j == null
        val stockChip = string(if (unknown) R.string.fs_chip_unknown else R.string.fs_chip_stock)
        val stockPreview = if (unknown) ClusterPreviewView.Mode.UNKNOWN else ClusterPreviewView.Mode.STOCK
        val read = FullscreenThemeView.Action(string(R.string.fs_check_action)) { readStatus() }
        val stop = FullscreenThemeView.Action(string(R.string.fs_stop_action)) { confirmDashboard(false) }
        val changeApp = FullscreenThemeView.Action(string(R.string.fs_change_app)) { chooseDashboardApp() }
        val state = when (step) {
            Step.READ -> FullscreenThemeView.State(tone, stockChip, stockPreview, 0, false, string(R.string.fs_check_title), message, primary = read)
            Step.UPDATE -> FullscreenThemeView.State(FullscreenThemeView.Tone.WARNING, stockChip, stockPreview, 0, false,
                string(R.string.fs_protocol_title), message, primary = read)
            Step.NONE -> FullscreenThemeView.State(FullscreenThemeView.Tone.WARNING, stockChip, stockPreview, 0, false, string(R.string.fs_small_title), message)
            Step.LOAD -> FullscreenThemeView.State(tone, stockChip, stockPreview, 1, false, string(R.string.fs_load_title), message,
                string(R.string.fs_load_expected), FullscreenThemeView.Action(string(R.string.fs_load_action)) { requestStart(THEME) })
            Step.WAIT -> {
                val recovering = restartPreparing || restartRound.isNotEmpty() || j?.optBoolean("recoveryPending") == true
                val closing = j?.optJSONObject("dashboard")?.optString("stage") == "CLOSING"
                val index = when { recovering -> -1; closing -> 4; own || starting -> 1; else -> -1 }
                FullscreenThemeView.State(FullscreenThemeView.Tone.ACTIVE, string(R.string.fs_chip_busy),
                    if (own) ClusterPreviewView.Mode.READY else stockPreview, index, true,
                    string(if (index == 1) R.string.fs_loading_title else R.string.fs_wait_title), message)
            }
            Step.CHOOSE -> FullscreenThemeView.State(FullscreenThemeView.Tone.OK, string(R.string.fs_chip_ready), ClusterPreviewView.Mode.READY, 2, false,
                string(R.string.fs_app_title), message, primary = FullscreenThemeView.Action(string(R.string.fs_app_action)) { chooseDashboardApp() })
            Step.START -> FullscreenThemeView.State(FullscreenThemeView.Tone.OK, string(R.string.fs_chip_ready), ClusterPreviewView.Mode.READY, 3, false,
                string(R.string.fs_start_title), message, string(R.string.fs_start_expected),
                FullscreenThemeView.Action(string(R.string.fs_start_action)) { confirmDashboard(true) }, secondary = listOf(changeApp))
            Step.CLOSED -> FullscreenThemeView.State(FullscreenThemeView.Tone.OK, string(R.string.fs_chip_ready), ClusterPreviewView.Mode.READY, 4, false,
                string(R.string.fs_closed_title), message, string(R.string.fs_restore_expected),
                FullscreenThemeView.Action(string(R.string.fs_finish_action)) { restoreButton.performClick() },
                secondary = listOf(FullscreenThemeView.Action(string(R.string.fs_relaunch_action)) { confirmDashboard(true) }, changeApp))
            Step.PROJECTING -> FullscreenThemeView.State(FullscreenThemeView.Tone.ACTIVE, string(R.string.fs_chip_active), ClusterPreviewView.Mode.FULLSCREEN, 3, true,
                string(R.string.fs_opening_title), message, secondary = listOf(stop))
            Step.CONFIRM -> FullscreenThemeView.State(FullscreenThemeView.Tone.ACTIVE, string(R.string.fs_chip_active), ClusterPreviewView.Mode.FULLSCREEN, 3, false,
                string(R.string.fs_observe_title), message, string(R.string.fs_start_expected),
                FullscreenThemeView.Action(string(R.string.fs_observe_action)) { confirmWhatISee() }, secondary = listOf(stop))
            Step.STOP -> FullscreenThemeView.State(FullscreenThemeView.Tone.ACTIVE, string(R.string.fs_chip_active), ClusterPreviewView.Mode.FULLSCREEN, 4, false,
                string(R.string.fs_done_title), message, primary = stop)
            Step.RECOVER -> FullscreenThemeView.State(FullscreenThemeView.Tone.DANGER, string(R.string.fs_chip_attention), ClusterPreviewView.Mode.ATTENTION, -1, false,
                string(R.string.fs_recover_title), message, string(R.string.fs_restore_expected),
                FullscreenThemeView.Action(string(R.string.fs_recover_action)) { restoreButton.performClick() }, danger = true,
                secondary = if (factoryApplyAvailable) listOf(FullscreenThemeView.Action(string(R.string.fs_factory_action)) { confirmIviRestore(applyFactory = true) }) else emptyList())
            Step.VERIFY -> FullscreenThemeView.State(FullscreenThemeView.Tone.WARNING, string(R.string.fs_chip_attention), ClusterPreviewView.Mode.STOCK, -1, false,
                string(R.string.fs_oem_question_title), message, string(R.string.fs_restore_expected),
                FullscreenThemeView.Action(string(R.string.fs_observe_action)) { prompts.reopen() })
        }
        page.render(state)
    }
    /** Three illustrated answers; only « map visible » records a user observation with the daemon. */
    private fun confirmWhatISee() {
        val shown = displayed
        val observedSession = displayedSession
        val name = shown?.takeIf { dashboardVariant(it) != null }?.optString("filename")
        val round = shown?.optJSONObject("dashboard")?.optString("round").orEmpty()
        if (shown == null || name == null || observedSession == null || !observeReady ||
            shown.optJSONObject("dashboard")?.optString("stage") != "OPEN_RECEIVED" || !round.matches(ROUND)) {
            notice(R.string.fs_state_changed); return
        }
        val observedActivation = activation
        fun answered(answer: String) = ClusterThemeDiagnostics.local(app, "dashboard-user-observation", "USER_OBSERVATION",
            "answer=$answer; round=$round; pixels reported by the user only; QML ACK UNKNOWN")
        confirmation?.dismiss()
        val dialog = GuidedDialog(activity).apply {
            title = string(R.string.fs_observe_dialog_title)
            choices = listOf(
                GuidedDialog.Choice(string(R.string.fs_observe_ok), ClusterPreviewView.Mode.FULLSCREEN) {
                    answered("fullscreen-visible")
                    submit("observe-dashboard") {
                        check(active && !closed && activation == observedActivation && observedSession.isCurrent() &&
                            displayed?.optString("filename") == name && displayed?.optJSONObject("dashboard")?.optString("round") == round) {
                            "Visual confirmation expired; nothing sent"
                        }
                        observedSession.observedDashboard(name, round)
                    }
                },
                GuidedDialog.Choice(string(R.string.fs_observe_stock), ClusterPreviewView.Mode.STOCK) {
                    answered("stock-unchanged")
                    confirmation = GuidedDialog(activity).apply {
                        title = string(R.string.fs_observe_stock); lead = string(R.string.fs_observe_stock_help)
                        positive = string(android.R.string.ok); negative = null
                    }.create().also { it.show() }
                },
                GuidedDialog.Choice(string(R.string.fs_observe_problem), ClusterPreviewView.Mode.ATTENTION) {
                    answered("abnormal"); restoreButton.performClick()
                })
        }.create()
        confirmation = dialog
        dialog.show()
    }

    private fun prepareRestartConfirmation(pinned: ClusterThemeSession) {
        if (restartPreparing || restartRound.isNotEmpty() || !recoveryBusy.compareAndSet(false, true)) return
        restartWizardRequested = false // One explicit continuation; polling never retries failed preparation.
        val requestedActivation = activation
        val reserved = reserve()
        if (reserved == null) { recoveryBusy.set(false); recoveryMessage(string(R.string.fs_busy)); return }
        restartPreparing = true
        recoveryGuidance?.dismiss(); confirmation?.dismiss()
        prompts.update("rcc", null)
        updateWorkflow(displayed)
        recovery.execute {
            var round = ""
            try {
                check(active && !closed && activation == requestedActivation && pinned.isCurrent() && !restartIntent.pending())
                check(!dashboardSlotsUnsettled && com.byd.dashcast.fission.FissionReleaseDebt.snapshot().isEmpty()) { "Projection resources have not finished closing" }
                val proof = JSONObject(pinned.prepareRestart(validity(reserved, foreground = true)))
                round = proof.getString("restartRound")
                check(round.matches(ROUND))
                val confirmedRound = round
                handler.post {
                    if (!active || closed || activation != requestedActivation || !pinned.isCurrent()) {
                        recovery.execute { runCatching { pinned.cancelRestart(confirmedRound) } }
                    } else {
                        restartRound = confirmedRound; restartSession = pinned
                        showRestartPopup(pinned, confirmedRound, requestedActivation)
                    }
                }
            } catch (error: Throwable) {
                if (round.isNotEmpty()) runCatching { pinned.cancelRestart(round) }
                handler.post { if (active && !closed && activation == requestedActivation)
                    recoveryMessage(string(R.string.cluster_restart_blocked) + "\n\n" + ClusterThemeDiagnostics.failure(error)) }
            } finally { restartPreparing = false; recoveryBusy.set(false); handler.post { updateWorkflow(displayed) } }
        }
    }
    private fun showRestartPopup(pinned: ClusterThemeSession, round: String, expectedActivation: Long) {
        var accepted = false
        val dialog = GuidedDialog(activity).apply {
            title = string(R.string.fs_restart_dialog_title)
            preview = ClusterPreviewView.Mode.STOCK
            steps = strings(R.string.fs_restart_step_check, R.string.fs_restart_step_screens)
            expected = string(R.string.fs_restart_expected)
            checks = strings(R.string.fs_check_parked)
            positive = string(R.string.fs_restart_confirm); danger = true
            // After an external `adb reboot fission-all`, verify instead of restarting again.
            neutral = string(R.string.fs_restart_done)
        }.create(onNeutral = { handler.post { if (active && !closed) confirmIviRestore() } }) { popup ->
            if (!active || closed || activation != expectedActivation || restartRound != round || !pinned.isCurrent()) {
                popup.dismiss(); return@create
            }
            accepted = true; popup.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false; restartPreparing = true
            restartRound = ""; restartSession = null; popup.dismiss(); confirmation = null
            updateWorkflow(displayed)
            recovery.execute {
                var dispatchAttempted = false
                var daemonConfirmed = false
                try {
                    check(active && !closed && activation == expectedActivation && pinned.isCurrent())
                    restartConnection(app).use { connection ->
                        check(active && !closed && activation == expectedActivation && pinned.isCurrent() && !restartIntent.pending())
                        check(!dashboardSlotsUnsettled && com.byd.dashcast.fission.FissionReleaseDebt.snapshot().isEmpty())
                        val receipt = JSONObject(pinned.confirmRestart(round)) // Re-hash and inventory AFTER consent and ADB authentication.
                        check(receipt.optBoolean("restartAuthorized") && receipt.optString("restartRound") == round)
                        daemonConfirmed = true
                        restartIntent.record(receipt.getString("filename"), round)
                        check(active && !closed && activation == expectedActivation && pinned.isCurrent())
                        dispatchAttempted = true
                        connection.dispatch() // At most one ADB OPEN; a disconnect is an unknown outcome.
                    }
                } catch (error: Throwable) {
                    ClusterThemeDiagnostics.local(app, "cluster-restart", "UNKNOWN", "dispatchAttempted=$dispatchAttempted; no replay; " + ClusterThemeDiagnostics.failure(error))
                    if (!dispatchAttempted && !restartIntent.pending()) runCatching { pinned.cancelRestart(round) }
                } finally {
                    restartPreparing = false; restartWizardRequested = false
                    handler.post {
                        if (!closed && active) {
                            updateWorkflow(displayed)
                            recoveryMessage(string(if (daemonConfirmed || restartIntent.pending()) R.string.cluster_restart_unknown else R.string.cluster_restart_blocked))
                        }
                    }
                }
            }
        }
        confirmation = dialog
        dialog.setOnDismissListener { if (!accepted) cancelRestartConfirmation() }
        dialog.show()
    }
    private fun cancelRestartConfirmation() {
        val round = restartRound; val pinned = restartSession
        restartRound = ""; restartSession = null; restartWizardRequested = false
        if (round.isNotEmpty() && pinned != null) recovery.execute { runCatching { pinned.cancelRestart(round) } }
        if (round.isNotEmpty()) handler.post { if (!closed) updateWorkflow(displayed) }
    }
    private fun confirmDashboard(open: Boolean) {
        if (!open) { dashboardTicket = null; dashboardCommandGeneration.incrementAndGet() }
        val variant = displayed?.let(::dashboardVariant)
        if (open && variant?.isRecoveryOnly == true) { notice(R.string.fs_recover_other); return }
        if (open && !ProxyClient.supportsProtocol(variant?.minimumProtocol ?: THEME.minimumProtocol)) {
            notice(R.string.fs_protocol_message, variant?.minimumProtocol ?: THEME.minimumProtocol)
            ClusterThemeDiagnostics.local(app, "dashboard-protocol-refused", "REFUSED", "Connected protocol${variant?.minimumProtocol ?: THEME.minimumProtocol} required; no dashboard command sent")
            return
        }
        if (variant == null || displayed?.optBoolean("rccDebt") != true) { notice(R.string.fs_state_changed); return }
        if (open && (displayed?.optJSONObject("dashboard")?.optBoolean("selectionReady") != true ||
                displayed?.optString("phase") != "observation" || displayed?.optBoolean("uncertain") == true ||
                displayed?.optJSONObject("dashboard")?.optString("stage") !in setOf("NONE", "CLOSED_RECEIVED"))) {
            notice(R.string.fs_state_changed); return
        }
        if (open && (dashboardPackage.isEmpty() || ClusterPrefs.isSmallClusterPanelLatched(app))) {
            notice(if (dashboardPackage.isEmpty()) R.string.fs_app_message else R.string.fs_small_message); return
        }
        val expectedActivation = activation
        val label = dashboardLabel
        confirmation?.dismiss()
        val dialog = GuidedDialog(activity).apply {
            if (open) {
                title = string(R.string.fs_start_dialog_title)
                preview = ClusterPreviewView.Mode.FULLSCREEN
                steps = listOf(string(R.string.fs_start_step_projection), string(R.string.fs_start_step_app, label), string(R.string.fs_start_step_stop))
                expected = string(R.string.fs_start_expected)
                positive = string(R.string.fs_start_confirm)
            } else {
                title = string(R.string.fs_stop_dialog_title)
                preview = ClusterPreviewView.Mode.STOCK
                steps = strings(R.string.fs_stop_step_close, R.string.fs_stop_step_keep)
                expected = string(R.string.fs_stop_expected)
                positive = string(R.string.fs_stop_confirm)
            }
            checks = strings(R.string.fs_check_parked, R.string.fs_check_cluster)
        }.create { popup ->
            if (closed || activation != expectedActivation) return@create
            popup.dismiss(); confirmation = null
            val request = {
                val reserved = reserve()
                if (reserved != null) {
                    val pinned = checkNotNull(session)
                    val expectedCommand = dashboardCommandGeneration.get()
                    val commandValidity = validity(reserved, foreground = true)
                    dashboardTicket = if (open) DashboardOpenTicket(expectedActivation, dashboardFilename, reserved, pinned, variant) else null
                    submit(if (open) "dashboard-open" else "dashboard-close") {
                        check(active && !closed && activation == expectedActivation && dashboardCommandGeneration.get() == expectedCommand && pinned.isCurrent()) {
                            "Dashboard click expired before IPC; no sequence sent"
                        }
                        pinned.dashboardProjection(open, commandValidity, variant)
                    }
                } else notice(R.string.fs_busy)
            }
            if (open) request() else stopDashboardSlots(request)
        }
        confirmation = dialog
        dialog.show()
    }
    private fun stopDashboardSlots(next: () -> Unit) {
        dashboardTicket = null; dashboardCommandGeneration.incrementAndGet()
        // Remote recovery must never queue behind a blocked local VD transaction.
        val adas = fullAppAdas
        if (adas == null) next() else adas.restore { delivered ->
            ClusterThemeDiagnostics.local(app, "full-app-adas-restore", if (delivered) "DELIVERED" else "UNKNOWN",
                "Existing complete ADAS recovery delivered=$delivered; pixels require observation")
            if (delivered) fullAppAdas = null
            next()
        }
        val fission = dashboardFission
        fission?.stopAll(Runnable {
            if (fission.getSlots().isEmpty() && com.byd.dashcast.fission.FissionReleaseDebt.snapshot().isEmpty()) {
                dashboardSlotsUnsettled = false; dashboardUi.get()?.releaseReservation()
            }
        })
    }
    private fun ticketCurrent(ticket: DashboardOpenTicket): Boolean = dashboardTicket === ticket && !closed && active &&
        activation == ticket.activation && dashboardFilename == ticket.filename && ticket.pinned.isCurrent() &&
        ProjectionCommandBus.ownsDiagnostic(ticket.reservation) && dashboardLaunchStateReady &&
        displayed?.let(::dashboardVariant) == ticket.variant
    private fun dashboardVariant(j: JSONObject): ClusterThemeVariant? =
        ClusterThemeVariant.values().singleOrNull { it.isDashboard && it.id == j.optString("variant") }
    private fun renderDashboard(j: JSONObject) {
        val variant = dashboardVariant(j)
        val dashboard = j.optJSONObject("dashboard")
        factoryApplyAvailable = variant != null && j.optBoolean("factoryApplyPending") &&
            !j.optBoolean("recoveryPending") && dashboard?.optBoolean("nativeDebt") == false
        val own = variant != null && j.optBoolean("rccDebt")
        val stage = dashboard?.optString("stage").orEmpty()
        if (variant != null && dashboard?.optBoolean("smallPanelSeen") == true) ClusterPrefs.latchSmallClusterPanel(app)
        dashboardFilename = j.optString("filename")
        dashboardLaunchStateReady = own && variant?.isRecoveryOnly != true && stage == "OPEN_RECEIVED" && !j.optBoolean("uncertain") &&
            !j.optBoolean("recoveryPending") && dashboard?.optBoolean("pending") == false
        openReady = own && variant?.isRecoveryOnly != true && dashboard?.optBoolean("selectionReady") == true &&
            !ClusterPrefs.isSmallClusterPanelLatched(app) &&
            j.optString("phase") == "observation" && !j.optBoolean("uncertain") && !j.optBoolean("recoveryPending") &&
            dashboard?.optBoolean("pending") == false && stage in setOf("NONE", "CLOSED_RECEIVED")
        observeReady = dashboardLaunchStateReady && dashboard?.optString("round")?.matches(ROUND) == true
        updateWorkflow(j)
        if (!own || variant?.isRecoveryOnly == true || stage == "UNKNOWN" || j.optBoolean("uncertain") || j.optBoolean("recoveryPending")) dashboardTicket = null
        val ticket = dashboardTicket
        if (own && stage == "OPEN_RECEIVED" && ticket != null && ticketCurrent(ticket) && ticket.dispatched.compareAndSet(false, true)) {
            val reserved = ticket.reservation
            if (dashboardFission == null) {
                dashboardFission = com.byd.dashcast.fission.FissionOrchestrator(app,
                    object : com.byd.dashcast.domain.cluster.ProjectionStateProvider {
                        override fun isProjectionActive() = com.byd.dashcast.cluster.ClusterService.getInstance()?.isProjectionActive() == true
                        override fun stopProjectionIfActive(onStopped: Runnable?) { onStopped?.run() }
                    }, object : com.byd.dashcast.fission.FissionOrchestrator.Callbacks {
                        override fun onSlotsChanged(slots: Collection<com.byd.dashcast.fission.FissionOrchestrator.SlotState>) {
                            dashboardUi.get()?.fullAppSlotReady(slots)
                            if (slots.isEmpty() && com.byd.dashcast.fission.FissionReleaseDebt.snapshot().isEmpty()) {
                                dashboardSlotsUnsettled = false; dashboardUi.get()?.releaseReservation()
                            }
                        }
                        override fun onDaemonBinderAcquired(binder: android.os.IBinder?) {}
                        override fun onStatusMessage(message: String?) {}
                        override fun onSlotError(pkg: String?, message: String?) { dashboardUi.get()?.show(message.orEmpty(), "dashboard-app-slot") }
                        override fun onProjectionConflict(proceedCallback: Runnable?) { dashboardUi.get()?.show("Projection conflict", "dashboard-app-conflict") }
                    })
            }
            dashboardSlotsUnsettled = true
            val owner = java.lang.ref.WeakReference(this)
            runCatching { dashboardFission?.startDashboardSlot(dashboardPackage, dashboardLabel, reserved, ticket.pinned, ticket.variant) { owner.get()?.ticketCurrent(ticket) == true } }
                .onFailure { show(ClusterThemeDiagnostics.failure(it), "dashboard-app-refused"); stopDashboardSlots {} }
        }
    }
    private fun fullAppSlotReady(slots: Collection<com.byd.dashcast.fission.FissionOrchestrator.SlotState>) {
        val ticket = dashboardTicket ?: return
        if (ticket.variant.isRecoveryOnly || !ticket.variant.isFullApp || !ticketCurrent(ticket) || fullAppAdas != null) return
        val target = slots.singleOrNull { it.pkg == dashboardPackage } ?: return
        val round = displayed?.optJSONObject("dashboard")?.optString("round").orEmpty()
        if (!round.matches(ROUND)) return
        fullAppAdas = com.byd.dashcast.cluster.FullAppProjectionAdas(app, ticket.pinned, ticket.reservation,
            round, dashboardPackage, target.displayId) {
                ticketCurrent(ticket) && displayed?.optJSONObject("dashboard")?.optString("round") == round
            }.also { it.start() }
    }
    private fun reserve(): ProjectionCommandSequencer.Session? = synchronized(reservationLock) {
        reservation?.takeIf { ProjectionCommandBus.isCurrent(it) }
            ?: ProjectionCommandBus.reserveDiagnostic()?.also {
                reservation = it; reservationFilename = ""; reservationDebt = false
            }
    }
    private fun validity(lease: ProjectionCommandSequencer.Session, foreground: Boolean = false): Binder {
        val expectedActivation = activation
        val expectedDashboardGeneration = dashboardCommandGeneration.get()
        return object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 1) return false
                data.enforceInterface("com.byd.dashcast.RccReservation")
                reply?.writeNoException(); reply?.writeInt(if (ProjectionCommandBus.isCurrent(lease) &&
                    (!foreground || !closed && active && activation == expectedActivation && dashboardCommandGeneration.get() == expectedDashboardGeneration)) 1 else 0)
                return true
            }
        }
    }
    private fun releaseReservation() = synchronized(reservationLock) {
        if (!ClusterThemeProjectionDebt.blocked(app) && !reservationDebt && !dashboardSlotsUnsettled) reservation?.let {
            ProjectionCommandBus.endSession(it); reservation = null; reservationFilename = ""
        }
    }
    private fun rememberReservationDebt(j: JSONObject, pinned: ClusterThemeSession) = synchronized(reservationLock) {
        if (!pinned.isCurrent() || reservation?.let(ProjectionCommandBus::isCurrent) != true || j.optString("transport") != "android-ivi") return@synchronized
        val name = j.optString("filename")
        if (!ClusterThemePolicy.ownedName(name)) return@synchronized
        if (reservationFilename.isEmpty() && j.optBoolean("rccDebt")) reservationFilename = name
        if (reservationFilename != name) return@synchronized
        if (j.optBoolean("rccDebt") || j.optBoolean("recoveryPending")) reservationDebt = true
        else if (j.has("rccDebt") && j.optString("state") in setOf("CLEAN", "REFUSED") &&
            j.optString("rccSafeNonce") == name.removePrefix(ClusterThemePolicy.PREFIX).removeSuffix(".rcc")) reservationDebt = false
    }
    private fun requestRecoveryAction() {
        if (restartPreparing || restartRound.isNotEmpty()) return
        if (!recoveryBusy.compareAndSet(false, true)) {
            recoveryMessage(string(R.string.fs_busy)); return
        }
        show(string(R.string.diag_cluster_rcc_restore_reading), "restore-status-pending")
        val requestedActivation = activation
        recovery.execute {
            var outcome: (() -> Unit)? = null
            try {
                check(ProxyClient.supportsProtocol(41)) { string(R.string.diag_cluster_rcc_restore_protocol) }
                val pinned = ClusterThemeSession.pin(app).also { session = it }
                val text = pinned.status()
                val current = JSONObject(text)
                render(text, lease.get(), pinned, requestedActivation)
                outcome = {
                    if (!closed && active && activation == requestedActivation && pinned.isCurrent()) {
                        val local = ClusterThemeProjectionDebt.nonce(app)
                        val remoteName = current.optString("filename")
                        when {
                            current.optBoolean("recoveryPending") -> recoveryMessage(string(R.string.diag_cluster_rcc_restore_pending)) {
                                active && pinned.isCurrent() && displayed?.optBoolean("recoveryPending") == true
                            }
                            current.optBoolean("rccDebt") && current.optString("variant") == "UNKNOWN" -> recoveryMessage(string(R.string.diag_cluster_rcc_restore_mismatch))
                            current.optString("transport") == "android-ivi" && current.optBoolean("rccDebt") -> {
                                if (!ClusterThemePolicy.ownedName(remoteName) || local != null && remoteName != ClusterThemePolicy.PREFIX + local + ".rcc") {
                                    recoveryMessage(string(R.string.diag_cluster_rcc_restore_mismatch))
                                } else if (current.optJSONObject("retirement")?.optBoolean("moved") == true &&
                                    ProxyClient.supportsProtocol(45) && !current.optBoolean("restartDispatchPending") && !restartIntent.pending())
                                    prepareRestartConfirmation(pinned)
                                else if (current.optBoolean("carrierMovable")) confirmIviRestore(retire = true)
                                else if (current.optBoolean("retirementAvailable")) showRecoveryGuidance(current, pinned, force = true)
                                else confirmIviRestore()
                            }
                            current.optString("transport") == "strict-renderer" && current.optBoolean("rccDebt") -> restore(false)
                            current.optBoolean("rccDebt") -> recoveryMessage(string(R.string.diag_cluster_rcc_restore_mismatch))
                            local != null && !current.optBoolean("rccDebt") -> confirmIviRestore()
                            else -> recoveryMessage(string(R.string.fs_restore_none))
                        }
                    }
                }
            } catch (error: Throwable) {
                show("Restore status unavailable: ${ClusterThemeDiagnostics.failure(error)}", "restore-status-failed", notify = false)
                outcome = { if (!closed && activation == requestedActivation) recoveryMessage(string(R.string.diag_cluster_rcc_restore_failed) + "\n\n" + ClusterThemeDiagnostics.failure(error)) }
            } finally { recoveryBusy.set(false); handler.post { outcome?.invoke(); refreshPrompt() } }
        }
    }
    private fun recoveryMessage(message: String, current: (() -> Boolean)? = null) {
        if (closed || activity.isFinishing || activity.isDestroyed) return
        recoveryGuidance?.dismiss()
        recoveryGuidanceCurrent = current
        recoveryGuidance = GuidedDialog(activity).apply {
            title = string(R.string.fs_info_title); lead = message
            positive = string(android.R.string.ok); negative = null
        }.create().also { it.show() }
        ClusterThemeDiagnostics.local(app, "restore-guidance", "LOCAL", message)
    }
    private fun showRecoveryGuidance(j: JSONObject, pinned: ClusterThemeSession, force: Boolean = false) {
        if (restartPreparing || restartRound.isNotEmpty()) return
        if (restartWizardRequested && active && !closed && j.optJSONObject("retirement")?.optBoolean("moved") == true &&
            !j.optBoolean("recoveryPending") && !j.optBoolean("restartDispatchPending") && !restartIntent.pending() &&
            ProxyClient.supportsProtocol(45)) {
            prepareRestartConfirmation(pinned); return
        }
        if (recoveryGuidanceCurrent?.invoke() == false) { recoveryGuidance?.dismiss(); recoveryGuidance = null; recoveryGuidanceCurrent = null }
        if (!closed && active && pinned.isCurrent() && j.optBoolean("rccDebt") && j.optString("transport") == "android-ivi" &&
            !ProxyClient.supportsProtocol(41)) {
            if (shownRecoveryGuidance.add("protocol41-required")) recoveryMessage(string(R.string.diag_cluster_rcc_restore_protocol))
            return
        }
        if (closed || !active || !pinned.isCurrent() || recoveryBusy.get() || j.optBoolean("recoveryPending") || confirmation?.isShowing == true ||
            !j.optBoolean("rccDebt") || j.optBoolean("defaultQueried") || j.optString("transport") != "android-ivi") return
        val eligible = j.optBoolean("retirementAvailable")
        val retired = j.optJSONObject("retirement")?.optBoolean("moved") == true
        val failed = j.optString("state") == "QUARANTINE" && j.optString("phase") in setOf("restore-query", "retirement-preflight", "retirement-intent", "retired-read-only-query", "carrier-retired")
        if (!eligible && !retired && !failed) return
        val name = j.optString("filename")
        if (!ClusterThemePolicy.ownedName(name)) return
        val localNonce = ClusterThemeProjectionDebt.nonce(app)
        val mismatch = localNonce != null && name != ClusterThemePolicy.PREFIX + localNonce + ".rcc"
        val prepare = eligible && !mismatch
        val key = listOf(name, j.optString("state"), j.optString("detail"), eligible, retired, localNonce).joinToString(":")
        if (recoveryGuidance?.isShowing == true && recoveryGuidanceKey == key || !force && key in shownRecoveryGuidance) return
        recoveryGuidance?.dismiss()
        prompts.update("rcc", null)
        val current = {
            active && !closed && pinned.isCurrent() && ClusterThemeProjectionDebt.nonce(app) == localNonce && displayedSession?.sharesConnection(pinned) == true && displayed?.let {
                it.optString("filename") == name && !it.optBoolean("recoveryPending") && it.optBoolean("rccDebt") && !it.optBoolean("defaultQueried") &&
                    it.optBoolean("retirementAvailable") == eligible && (it.optJSONObject("retirement")?.optBoolean("moved") == true) == retired
            } == true
        }
        val message = when {
            mismatch -> string(R.string.diag_cluster_rcc_restore_mismatch)
            eligible -> string(R.string.fs_guidance_same)
            retired -> string(R.string.fs_guidance_retired)
            else -> string(R.string.diag_cluster_rcc_restore_failed)
        } + if (failed) "\n\n" + j.optString("detail") else ""
        val dialog = GuidedDialog(activity).apply {
            title = string(R.string.fs_info_title); lead = message
            positive = string(if (prepare) R.string.fs_retire_confirm else android.R.string.ok)
        }.create { popup ->
            if (recoveryGuidance !== popup || !current()) {
                popup.dismiss(); ClusterThemeDiagnostics.local(app, "restore-guidance", "REFUSED", "Expired/replaced recovery guidance; no action sent")
                return@create
            }
            popup.dismiss()
            ClusterThemeDiagnostics.local(app, "restore-guidance", "LOCAL", "${if (prepare) "prepare-confirmation-requested" else "acknowledged"}; no OEM command or retirement sent by guidance")
            if (prepare) confirmIviRestore(retire = true)
        }
        recoveryGuidance = dialog; recoveryGuidanceKey = key; recoveryGuidanceCurrent = current
        shownRecoveryGuidance.add(key); while (shownRecoveryGuidance.size > 16) shownRecoveryGuidance.remove(shownRecoveryGuidance.first())
        dialog.setOnCancelListener { ClusterThemeDiagnostics.local(app, "restore-guidance", "LOCAL", "cancel; recovery debt unchanged; no action sent") }
        dialog.setOnDismissListener { if (recoveryGuidance === dialog) { recoveryGuidance = null; recoveryGuidanceCurrent = null } }
        ClusterThemeDiagnostics.local(app, "restore-guidance", "LOCAL", "shown; eligible=$eligible retired=$retired state=${j.optString("state")} filename=$name; no automatic recovery/retirement/observation")
        dialog.show()
    }
    private fun confirmIviRestore(retire: Boolean = false, applyFactory: Boolean = false) {
        recoveryGuidance?.dismiss(); recoveryGuidance = null; recoveryGuidanceCurrent = null
        val expectedActivation = activation
        val expectedSession = displayedSession
        val expectedFilename = displayed?.optString("filename")
        val retired = displayed?.let { it.optBoolean("rccDebt") && it.optJSONObject("retirement")?.optBoolean("moved") == true } == true
        val bootstrapOnly = displayed?.optJSONObject("dashboard")?.optBoolean("bootstrapStarted") == true && displayed?.optBoolean("mutated") == false
        val projection = displayed?.let(::dashboardVariant) != null
        confirmation?.dismiss()
        val dialog = GuidedDialog(activity).apply {
            preview = ClusterPreviewView.Mode.STOCK
            checks = strings(R.string.fs_check_parked, R.string.fs_check_restore)
            when {
                applyFactory -> {
                    title = string(R.string.fs_factory_dialog_title)
                    steps = strings(R.string.fs_factory_step, R.string.fs_factory_step_none)
                    expected = string(R.string.fs_restore_expected); positive = string(R.string.fs_factory_action)
                }
                retire -> {
                    title = string(R.string.fs_retire_dialog_title); preview = null
                    steps = strings(R.string.fs_retire_step_move, R.string.fs_retire_step_no_restart)
                    expected = string(R.string.fs_retire_expected); positive = string(R.string.fs_retire_confirm)
                }
                retired -> {
                    title = string(R.string.fs_verify_dialog_title)
                    steps = strings(R.string.fs_verify_step)
                    expected = string(R.string.fs_restore_expected); positive = string(R.string.fs_verify_confirm)
                }
                else -> {
                    title = string(R.string.fs_restore_dialog_title)
                    steps = when {
                        bootstrapOnly -> strings(R.string.fs_bootstrap_step, R.string.fs_restore_step_restart)
                        projection -> strings(R.string.fs_restore_step_close, R.string.fs_restore_step_default, R.string.fs_restore_step_restart)
                        else -> strings(R.string.fs_restore_step_default, R.string.fs_restore_step_restart)
                    }
                    expected = string(R.string.fs_restore_expected); positive = string(R.string.fs_restore_confirm)
                }
            }
        }.create { popup ->
            popup.dismiss(); confirmation = null
            if (closed || activation != expectedActivation || expectedSession != null && (!expectedSession.isCurrent() || displayedSession?.sharesConnection(expectedSession) != true || displayed?.optString("filename") != expectedFilename) ||
                retire && displayed?.let { it.optBoolean("retirementAvailable") || it.optBoolean("carrierMovable") ||
                    it.optJSONObject("retirement")?.optBoolean("moved") == false } != true) {
                recoveryMessage(string(R.string.diag_cluster_rcc_restore_expired))
            } else restore(true, retire, applyFactory)
        }
        confirmation = dialog
        dialog.show()
    }
    private fun restore(ivi: Boolean, retire: Boolean = false, applyFactory: Boolean = false) {
        if (!recoveryBusy.compareAndSet(false, true)) { notice(R.string.fs_busy); return }
        shownRecoveryGuidance.clear()
        val reserved = if (ivi) reserve() else null
        if (ivi && reserved == null) {
            recoveryBusy.set(false)
            val message = string(R.string.fs_busy)
            show(message, "restore-reservation-refused", notify = false); recoveryMessage(message); return
        }
        displayed?.let { j -> displayedSession?.let { rememberReservationDebt(j, it) } }
        val requestedLease = lease.get(); val requestedActivation = activation
        val dispatch = { recovery.execute {
            try {
                val s = session ?: ClusterThemeSession.pin(app).also { session = it }
                render(if (applyFactory) s.applyFactorySelection(validity(checkNotNull(reserved))) else if (ivi) s.restoreIvi(validity(checkNotNull(reserved)), retire) else s.restore(),
                    requestedLease, s, requestedActivation)
            } catch (error: Throwable) {
                val failure = ClusterThemeDiagnostics.failure(error)
                show("Restore UNKNOWN/refused: $failure. No reconnect/replay.", "restore")
                handler.post { if (!closed && activation == requestedActivation) recoveryMessage(string(R.string.diag_cluster_rcc_restore_failed) + "\n\n" + failure) }
            }
            finally { recoveryBusy.set(false); releaseReservation(); handler.post { refreshPrompt(); displayed?.let { j -> displayedSession?.let { showRecoveryGuidance(j, it) } } } }
        } }
        if (ivi) {
            val debt = displayed?.optJSONObject("fullAppAdas")
            val pinned = session
            if (fullAppAdas == null && dashboardVariant(displayed ?: JSONObject())?.isFullApp == true &&
                debt?.optBoolean("debt") == true && pinned != null && reserved != null) {
                val round = debt.optString("round")
                if (round.matches(ROUND)) fullAppAdas =
                    com.byd.dashcast.cluster.FullAppProjectionAdas(app, pinned, reserved, round, "", -1) { false }
            }
            val adas = fullAppAdas
            if (adas != null) adas.restore { delivered ->
                if (delivered) fullAppAdas = null
                dispatch()
            } else dispatch()
        } else dispatch()
    }
    private fun begin(variant: ClusterThemeVariant) {
        if (variant.isRecoveryOnly) { notice(R.string.fs_recover_other); return }
        if (Build.VERSION.SDK_INT != 29 || !Platform.get().isAutoDetectedDiLink3()) {
            show(string(R.string.diag_cluster_rcc_ivi_platform), "ivi-platform-refused", notify = false); notice(R.string.fs_platform); return
        }
        if (variant.isDashboard && !ProxyClient.supportsProtocol(variant.minimumProtocol)) {
            notice(R.string.fs_protocol_message, variant.minimumProtocol)
            ClusterThemeDiagnostics.local(app, "dashboard-protocol-refused", "REFUSED", "Connected protocol${variant.minimumProtocol} required; no start IPC sent")
            return
        }
        val reserved = reserve()
        if (reserved == null) { show(string(R.string.fs_busy), "ivi-reservation-refused", notify = false); notice(R.string.fs_busy); return }
        if (variant.isDashboard && ClusterPrefs.isSmallClusterPanelLatched(app)) {
            releaseReservation(); notice(R.string.fs_small_message)
            ClusterThemeDiagnostics.local(app, "dashboard-small-panel-refused", "REFUSED", "Previously observed small-panel latch; no bootstrap, RCC load or size-forcing command sent")
            return
        }
        // Reserve request lane BEFORE global busy ownership. Polling cannot strand the claim.
        if (!busy.compareAndSet(false, true)) { releaseReservation(); show("Another request is active. No start submitted.", "start-busy"); return }
        if (recoveryBusy.get() || diagClaimed.get() || !claimDiag()) {
            busy.set(false); releaseReservation()
            show("Diagnostic lane unavailable. No start submitted.", "start-refused", notify = false); notice(R.string.fs_busy); return
        }
        diagClaimed.set(true); val myLease = lease.incrementAndGet(); val requestedActivation = activation; abortBeforeSend.set(false)
        starting = true; updateWorkflow(displayed)
        show("${variant.id} preflight pending. Parked is your assertion, not a detected vehicle state.", "start-preflight")
        work.execute {
            var ipcBegan = false
            try {
                val s = session ?: ClusterThemeSession.pin(app).also { session = it }
                val before = s.status()
                if (JSONObject(before).optJSONObject("nativeMode")?.optBoolean("debt") == true || ClusterNativeProjectionDebt.blocked(app)) {
                    render(before, myLease, s, requestedActivation)
                    releaseClaim(myLease)
                    handler.post { if (active && !closed) showNativeBlock() }
                } else render(start(variant, reserved) { check(!closed && activation == requestedActivation && !abortBeforeSend.get()) { "Cancelled before start transmission" }; ipcBegan = true }, myLease, s, requestedActivation)
            }
            catch (t: Throwable) {
                if (!ipcBegan) releaseClaim(myLease)
                show(if (ipcBegan) "UNKNOWN after start transmission: ${ClusterThemeDiagnostics.failure(t)}. No replay; use Restore."
                    else "REFUSED before start transmission: ${ClusterThemeDiagnostics.failure(t)}. No cluster load requested.", "start-failed")
            } finally { busy.set(false); starting = false; handler.post { refreshPrompt(); if (!closed) updateWorkflow(displayed) } }
        }
        pendingNotice()
    }
    private fun start(variant: ClusterThemeVariant, reserved: ProjectionCommandSequencer.Session, beforeTransaction: () -> Unit): String {
        check(!variant.isRecoveryOnly) { "Retired full-app profile cannot be staged" }
        val s = session ?: ClusterThemeSession.pin(app).also { session = it }
        check(ProxyClient.supportsProtocol(41)) { string(R.string.diag_cluster_rcc_restore_protocol) }
        ClusterThemeDiagnostics.local(app, "prepare-source", "LOCAL", "Preparing ${variant.id} bounded app-owned carrier; no cluster load requested yet.")
        val parent = File(activity.getExternalFilesDir(null) ?: error("Own external files unavailable"), "cluster-live-source")
        check(parent.isDirectory || parent.mkdirs()) { "Own external files unavailable" }
        val source = File.createTempFile("${variant.id}-", ".rcc", parent)
        try {
            activity.assets.open(variant.asset).use { input -> FileOutputStream(source).use { output ->
                val buffer = ByteArray(65536); var total = 0L
                while (true) { check(!abortBeforeSend.get()); val n = input.read(buffer); if (n < 0) break
                    total += n; check(total <= ClusterThemePolicy.MAX_CARRIER); output.write(buffer, 0, n) }
                output.fd.sync()
            } }
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { s.startIvi(it, variant, validity(reserved, variant.isDashboard), beforeTransaction) }
        } finally { source.delete() } // Only this app's source; daemon owns the carrier.
    }
    private fun submit(operation: String, automatic: Boolean = false, action: () -> String) {
        if (!busy.compareAndSet(false, true)) {
            if (!automatic) show("Another request is active. No request submitted.", "$operation-busy")
            return
        }
        val myLease = lease.get()
        val requestedActivation = activation
        statusPending = operation == "status"
        work.execute {
            try { val text = action(); session?.let { render(text, myLease, it, requestedActivation) } } catch (t: Throwable) {
                if (operation == "status") handler.post {
                    if (active && activation == requestedActivation) {
                        displayed = null; displayedSession = null; prompts.update("rcc", null); nativeStatusUnavailable()
                        updateWorkflow(null)
                    }
                }
                show("REFUSED/UNKNOWN: ${ClusterThemeDiagnostics.failure(t)}. No request replayed.", "$operation-failed", notify = false)
                if (!automatic) notice(if (operation == "status") R.string.fs_check_failed else R.string.fs_failed)
            }
            finally { statusPending = false; busy.set(false); handler.post { refreshPrompt() } }
        }
        pendingNotice()
    }
    private fun pendingNotice() { handler.postDelayed({ if (busy.get()) show("Request pending: remote completion UNKNOWN. Restore remains available.", "request-pending") }, 3000) }
    private fun releaseClaim(expected: Long) {
        if (lease.get() == expected && diagClaimed.compareAndSet(true, false)) releaseDiag()
        if (lease.get() == expected) releaseReservation()
    }
    private fun render(text: String, expectedLease: Long, pinned: ClusterThemeSession, requestedActivation: Long) {
        val j = JSONObject(text)
        settleRestartIntent(j, pinned)
        rememberReservationDebt(j, pinned)
        if (j.getString("state") in setOf("IDLE", "CLEAN", "REFUSED")) releaseClaim(expectedLease)
        val retirement = j.optJSONObject("retirement")
        val retiredPending = j.optBoolean("rccDebt") && retirement?.optBoolean("moved") == true
        val guided = if (retiredPending && !j.optBoolean("defaultQueried")) string(R.string.fs_guidance_retired) + "\n\n" else ""
        val evidenceText = j.optJSONObject("loadEvidence")?.let { "Load receipts: send=${it.optString("send", "UNKNOWN")}; selection=${it.optString("selection", "UNKNOWN")}; " +
            "ack2=${it.optString("ack2", "UNKNOWN")}; pixels=${it.optString("pixels", "UNKNOWN")}\n\n" }.orEmpty()
        val pending = if (j.optBoolean("recoveryPending")) string(R.string.diag_cluster_rcc_restore_pending) + "\n\n" else ""
        val summary = pending + guided + evidenceText + "${j.getString("state")}: ${j.getString("detail")}\nProfile ${j.optString("variant", "legacy marker")}; transport=${j.optString("transport", "legacy strict renderer")}; native identity=${j.optString("nativeIdentity", "UNKNOWN")}; phase ${j.optString("phase", "unrecorded")}; daemon UID ${j.getInt("daemonUid")}; default queried=${j.getBoolean("defaultQueried")}; marker/theme/OEM observed by user=${j.getBoolean("markerObservedByUser")}/${j.optBoolean("themeObservedByUser")}/${j.getBoolean("oemObservedByUser")}"
        handler.post {
            if (!closed && pinned.isCurrent()) {
                if (active && activation == requestedActivation) {
                    displayed = j; displayedSession = pinned
                    renderDashboard(j)
                    if (j.has("nativeMode")) nativeStatus(text, pinned)
                    refreshPrompt()
                    showRecoveryGuidance(j, pinned)
                }
                if (j.getString("state") !in setOf("IDLE", "CLEAN", "REFUSED") && lease.get() == expectedLease && !diagClaimed.get() && !otherBusy() && claimDiag()) {
                    diagClaimed.set(true); lease.incrementAndGet()
                }
                if (summary != lastSummary) { lastSummary = summary; transcript("Physical cluster test status receipt (historical; pixels UNKNOWN): $summary") }
            }
        }
        show(summary)
    }
    private fun settleRestartIntent(receipt: JSONObject, pinned: ClusterThemeSession) {
        if (!restartIntent.pending() || receipt.optString("state") != "CLEAN" || receipt.optBoolean("rccDebt", true) ||
            !(receipt.optBoolean("oemObservedByUser") || receipt.optJSONObject("restartCompleted") != null) || !restartSettlementPending.compareAndSet(false, true)) return
        val name = receipt.optString("filename")
        work.execute {
            try {
                check(pinned.isCurrent())
                val fresh = JSONObject(pinned.status())
                val historical = fresh.optJSONObject("restartCompleted")?.let(com.byd.dashcast.clusterlive.ClusterRestartCompletion::parse)
                check(pinned.isCurrent() && fresh.optString("filename") == name && fresh.optString("state") == "CLEAN" &&
                    !fresh.optBoolean("rccDebt", true) && (fresh.optBoolean("oemObservedByUser") || historical != null))
                restartIntent.observedRestored(name, historical)
            } catch (error: Throwable) {
                ClusterThemeDiagnostics.local(app, "restart-settlement", "UNKNOWN", ClusterThemeDiagnostics.failure(error))
            } finally { restartSettlementPending.set(false) }
        }
    }
    /** Technical text goes to the folded details; refusals and failures also raise the page banner. */
    private fun show(text: String, operation: String? = null, notify: Boolean = true) {
        if (operation != null) ClusterThemeDiagnostics.local(app, operation, "LOCAL", text)
        handler.post { if (!closed && !activity.isDestroyed) page.details(text) }
        if (notify && operation != null && (operation.startsWith("dashboard") || operation.endsWith("failed") || operation.endsWith("refused") || operation.contains("busy"))) {
            handler.post { if (!closed && !activity.isDestroyed) page.notice(string(if (operation.contains("busy")) R.string.fs_busy else R.string.fs_failed)) }
        }
    }
    private fun notice(id: Int, vararg args: Any) {
        val text = string(id, *args)
        handler.post { if (!closed && !activity.isDestroyed) { page.details(text); page.notice(text) } }
    }
    fun setActive(value: Boolean) {
        if (!value) { fullAppAdas?.cancelHide(); dashboardTicket = null; dashboardCommandGeneration.incrementAndGet() }
        active = value
        activation++
        displayed = null; displayedSession = null
        handler.removeCallbacks(poll)
        if (!value) {
            cancelRestartConfirmation()
            confirmation?.dismiss(); confirmation = null
            recoveryGuidance?.dismiss(); recoveryGuidance = null; recoveryGuidanceCurrent = null
            displayed = null; displayedSession = null
            prompts.update("rcc", null); prompts.update("blocked", null)
        } else {
            // Only status from an already connected daemon; no OEM query, connect or replay.
            readStatus()
            handler.postDelayed(poll, 1500)
        }
    }
    private fun rccKey(j: JSONObject): String =
        listOf("filename", "variant", "transport", "phase", "state", "mutated", "defaultQueried").joinToString(":") { (j.opt(it) ?: JSONObject.NULL).toString() }
    /** Only the return to the factory display is asked as a visual question; legacy test observations are retired. */
    private fun refreshPrompt() {
        val j = displayed
        val s = displayedSession
        if (!active || closed || busy.get() && !statusPending || observationPending.get() || recoveryBusy.get() || j == null || s == null || !s.isCurrent() ||
            j.optString("transport") == "android-ivi" && !ProxyClient.supportsProtocol(41) ||
            j.optBoolean("recoveryPending") || !(j.optBoolean("mutated") || j.optJSONObject("dashboard")?.optBoolean("bootstrapRecoverySettled") == true) ||
            !ClusterThemePolicy.ownedName(j.optString("filename")) ||
            !(j.optBoolean("defaultQueried") && !j.optBoolean("oemObservedByUser") && j.optString("state") in setOf("DEFAULT_QUERIED", "QUARANTINE"))) {
            prompts.update("rcc", null); return
        }
        val snapshot = rccKey(j)
        val filename = j.optString("filename")
        prompts.update("rcc", ClusterObservationPrompts.Question("rcc:$snapshot", 90, s,
            R.string.fs_oem_question_title, R.string.fs_oem_question_message, R.string.fs_oem_question_yes,
            current = { active && !closed && (!busy.get() || statusPending) && !observationPending.get() && !recoveryBusy.get() && displayedSession === s && displayed?.let(::rccKey) == snapshot },
            answer = {
                prompts.update("rcc", null)
                val expectedActivation = activation
                observe("observe-oem") {
                    check(session === s && s.isCurrent()) { "Visual confirmation belongs to a replaced session" }
                    val current = JSONObject(s.status())
                    check(active && !closed && activation == expectedActivation && rccKey(current) == snapshot) { "Visual confirmation expired; inspect the current cluster again" }
                    s.observedOem(filename)
                }
            }, preview = ClusterPreviewView.Mode.STOCK))
    }
    private fun observe(operation: String, action: () -> String) {
        if (!observationPending.compareAndSet(false, true)) return
        val expectedLease = lease.get()
        val requestedActivation = activation
        work.execute {
            var acquired = false
            try {
                check(active && !closed && activation == requestedActivation) { "Visual confirmation screen changed" }
                acquired = busy.compareAndSet(false, true)
                check(acquired) { "Another request replaced visual confirmation" }
                val text = action()
                val restored = JSONObject(text)
                if (restored.optString("state") == "CLEAN" && restored.optBoolean("oemObservedByUser") && !restored.optBoolean("rccDebt"))
                    restartIntent.observedRestored(restored.getString("filename"))
                session?.let { render(text, expectedLease, it, requestedActivation) }
            } catch (t: Throwable) { show("Visual confirmation refused: ${ClusterThemeDiagnostics.failure(t)}. No load/replay.", "$operation-failed") }
            finally { if (acquired) busy.set(false); observationPending.set(false); handler.post { refreshPrompt() } }
        }
    }
    fun close() { setActive(false); closed = true; confirmation?.dismiss(); confirmation = null; handler.removeCallbacksAndMessages(null) }
}
