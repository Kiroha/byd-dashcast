package com.byd.dashcast.ui.diag

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Binder
import android.os.Parcel
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.byd.dashcast.clusterlive.ClusterNativeProjectionDebt
import com.byd.dashcast.clusterlive.ClusterThemeProjectionDebt
import com.byd.dashcast.cluster.display.ProjectionCommandBus
import com.byd.dashcast.cluster.display.ProjectionCommandSequencer
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.clusterlive.ClusterThemePolicy
import com.byd.dashcast.clusterlive.ClusterThemeDiagnostics
import com.byd.dashcast.clusterlive.ClusterThemeVariant
import com.byd.dashcast.proxy.ClusterThemeSession
import com.byd.dashcast.R
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Reserved Restore lane, independent of both the command lane and Diag's global busy gate. */
internal class ClusterThemeLiveControls(private val activity: Activity, header: LinearLayout, controls: LinearLayout,
    private val claimDiag: () -> Boolean, private val releaseDiag: () -> Unit,
    private val otherBusy: () -> Boolean, private val transcript: (String) -> Unit,
    statusContainer: LinearLayout = header, reportShortcut: (LinearLayout) -> Unit = {},
    private val nativeStatus: (String, ClusterThemeSession) -> Unit = { _, _ -> },
    private val prompts: ClusterObservationPrompts = ClusterObservationPrompts(activity),
    private val nativeStatusUnavailable: () -> Unit = {},
    private val openClusterPage: () -> Unit = {}, dashboardContainer: LinearLayout? = null) {
    companion object {
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
    @Volatile private var active = false
    @Volatile private var activation = 0L
    private val dashboardCommandGeneration = AtomicLong(0)
    private class DashboardOpenTicket(val activation: Long, val filename: String,
        val reservation: ProjectionCommandSequencer.Session, val pinned: ClusterThemeSession) {
        val dispatched = AtomicBoolean(false)
    }
    @Volatile private var dashboardTicket: DashboardOpenTicket? = null
    @Volatile private var dashboardFilename = ""
    @Volatile private var dashboardLaunchStateReady = false
    @Volatile private var statusPending = false
    private val observationPending = AtomicBoolean(false)
    private var displayed: JSONObject? = null
    private var displayedSession: ClusterThemeSession? = null
    private val restoreButton = Button(activity)
    private val retireButton = Button(activity)
    private val cornerVerifyButton = Button(activity)
    private var dashboardOpenButton: Button? = null
    private var dashboardObserveButton: Button? = null
    private val dashboardStatus = TextView(activity).apply {
        textSize = 16f; text = activity.getString(R.string.diag_cluster_dashboard_waiting)
        accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    @Volatile private var queryPending = false
    private val status = TextView(activity).apply {
        textSize = 14f
        text = "Physical cluster state UNKNOWN. Read the current connected daemon status before drawing conclusions."
    }
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
        dashboardContainer?.let { host ->
            host.addView(TextView(activity).apply { text = activity.getString(R.string.diag_cluster_dashboard_title); textSize = 20f; isAccessibilityHeading = true })
            host.addView(TextView(activity).apply { text = activity.getString(R.string.diag_cluster_dashboard_help); textSize = 15f })
            host.addView(dashboardStatus)
            fun action(label: Int, run: () -> Unit) = Button(activity).apply {
                text = activity.getString(label); isAllCaps = false
                minHeight = (48 * activity.resources.displayMetrics.density).toInt()
                contentDescription = text; setOnClickListener { run() }
            }.also(host::addView)
            action(R.string.diag_cluster_dashboard_read) {
                submit("status") { ClusterThemeSession.pin(app).also { session = it }.status() }
            }
            action(R.string.diag_cluster_dashboard_prepare) { requestStart(ClusterThemeVariant.FULL_DASHBOARD_21F7735C) }
            action(R.string.diag_cluster_dashboard_choose_app) { chooseDashboardApp() }
            dashboardOpenButton = action(R.string.diag_cluster_dashboard_open) { confirmDashboard(true) }.apply { isEnabled = false }
            action(R.string.diag_cluster_dashboard_close) { confirmDashboard(false) }
            dashboardObserveButton = action(R.string.diag_cluster_dashboard_observe) {
                val name = displayed?.takeIf { it.optString("variant") == ClusterThemeVariant.FULL_DASHBOARD_21F7735C.id }?.optString("filename")
                if (name == null) { dashboardMessage(R.string.diag_cluster_dashboard_prepare_first); return@action }
                val round = displayed?.optJSONObject("dashboard")?.optString("round").orEmpty()
                val observedSession = displayedSession
                if (observedSession == null) { dashboardMessage(R.string.diag_cluster_dashboard_waiting); return@action }
                val observedActivation = activation
                if (displayed?.optJSONObject("dashboard")?.optString("stage") != "OPEN_RECEIVED" || !round.matches(Regex("[0-9a-f]{32}"))) {
                    dashboardMessage(R.string.diag_cluster_dashboard_waiting); return@action
                }
                AlertDialog.Builder(activity).setTitle(R.string.diag_cluster_dashboard_observe)
                    .setMessage(R.string.diag_cluster_dashboard_observe_message).setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ -> submit("observe-dashboard") {
                        check(active && !closed && activation == observedActivation && observedSession.isCurrent() &&
                            displayed?.optString("filename") == name && displayed?.optJSONObject("dashboard")?.optString("round") == round)
                        observedSession.observedDashboard(name, round)
                    } }.show()
            }.apply { isEnabled = false }
        }
        // Keep recovery in the fixed header; detailed status can live in the cluster page.
        val headerIndex = 0
        statusContainer.addView(status)
        header.addView(restoreButton.apply {
            text = activity.getString(R.string.diag_cluster_rcc_restore)
            isAllCaps = false; minHeight = (48 * activity.resources.displayMetrics.density).toInt()
            contentDescription = activity.getString(R.string.diag_cluster_rcc_restore)
            setOnClickListener {
                confirmation?.dismiss(); confirmation = null
                abortBeforeSend.set(true)
                dashboardTicket = null; dashboardCommandGeneration.incrementAndGet()
                ClusterThemeDiagnostics.local(app, "restore-button", "LOCAL", "Opening Cluster page and reading current pinned daemon status before choosing recovery; no OEM request yet.")
                openClusterPage()
                stopDashboardSlots { requestRecoveryAction() }
            }
        }, headerIndex)
        controls.addView(Button(activity).apply {
            text = activity.getString(R.string.diag_cluster_oem_query_button)
            isAllCaps = false
            minHeight = (48 * activity.resources.displayMetrics.density).toInt()
            setOnClickListener {
                if (!queryPending && !recoveryBusy.get() && !otherBusy() && !diagClaimed.get()) {
                    submit("oem-selection-query") { ClusterThemeSession.pin(app).also { session = it }.querySelection() }
                } else show(activity.getString(R.string.diag_cluster_oem_query_busy), "oem-query-busy")
            }
        })
        controls.addView(TextView(activity).apply {
            text = activity.getString(R.string.diag_cluster_oem_query_explanation)
            textSize = 14f
        })
        reportShortcut(controls)
        controls.addView(Button(activity).apply {
            text = activity.getString(R.string.diag_cluster_rcc_corners_button)
            isAllCaps = false; minHeight = (48 * activity.resources.displayMetrics.density).toInt()
            setOnClickListener {
                if (!queryPending && !observationPending.get() && !busy.get() && !recoveryBusy.get() && !otherBusy() && !diagClaimed.get()) requestStart(ClusterThemeVariant.CORNER_MARKER_21F7735C)
                else showBusy()
            }
        })
        controls.addView(cornerVerifyButton.apply {
            text = activity.getString(R.string.diag_cluster_rcc_corners_verify_button)
            isAllCaps = false; minHeight = (48 * activity.resources.displayMetrics.density).toInt()
            visibility = android.view.View.GONE
            setOnClickListener { confirmation?.dismiss(); confirmIviRestore(verifyCorners = true) }
        })
        controls.addView(TextView(activity).apply {
            text = activity.getString(R.string.diag_cluster_rcc_ivi_help) + "\n\n" + activity.getString(R.string.diag_cluster_rcc_cohort_help)
            textSize = 14f
        })
        controls.addView(retireButton.apply {
            text = activity.getString(R.string.diag_cluster_rcc_retire_button)
            isAllCaps = false; minHeight = (48 * activity.resources.displayMetrics.density).toInt()
            visibility = android.view.View.GONE
            setOnClickListener { confirmation?.dismiss(); abortBeforeSend.set(true); confirmIviRestore(retire = true) }
        })
        controls.addView(TextView(activity).apply {
            text = activity.getString(R.string.diag_cluster_rcc_ivi_identity)
            textSize = 14f
        })
        controls.addView(Button(activity).apply {
            text = "Read current connected daemon test status (no load)"
            setOnClickListener { submit("status") { ClusterThemeSession.pin(app).also { session = it }.status() } }
        })
        controls.addView(Button(activity).apply {
            text = activity.getString(R.string.diag_cluster_observation_again)
            isAllCaps = false
            minHeight = (48 * activity.resources.displayMetrics.density).toInt()
            setOnClickListener { prompts.reopen() }
        })
    }
    private fun requestStart(variant: ClusterThemeVariant) {
        if (ClusterNativeProjectionDebt.blocked(app) || displayed?.optJSONObject("nativeMode")?.optBoolean("debt") == true) {
            showNativeBlock()
        } else if (ClusterThemeProjectionDebt.blocked(app)) {
            show(activity.getString(R.string.diag_cluster_rcc_debt)); confirmIviRestore()
        } else confirm(variant)
    }
    private fun showNativeBlock() {
        ClusterThemeDiagnostics.local(app, "rcc-start-blocked", "REFUSED", "Native mode recovery/visual normal confirmation remains pending; no RCC load submitted. Return 18 is manual; answering the observation never starts RCC.")
        show(activity.getString(R.string.diag_cluster_rcc_blocked_message))
        prompts.explainBlocked()
    }
    private fun confirm(variant: ClusterThemeVariant) {
        val checks = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val assertion = CheckBox(activity).apply { text = activity.getString(R.string.diag_cluster_native_parked) }
        val firmware = CheckBox(activity).apply { text = activity.getString(R.string.diag_cluster_native_firmware) }
        checks.addView(assertion); checks.addView(firmware)
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.diag_cluster_rcc_ivi_title)
            .setMessage(when (variant) {
                ClusterThemeVariant.CORNER_MARKER, ClusterThemeVariant.CORNER_MARKER_21F7735C -> R.string.diag_cluster_rcc_corners_confirm
                ClusterThemeVariant.MARKER -> R.string.diag_cluster_rcc_ivi_marker_confirm
                ClusterThemeVariant.FULL_SURFACE_TEST -> R.string.diag_cluster_rcc_ivi_theme_confirm
                ClusterThemeVariant.FULL_DASHBOARD_21F7735C -> R.string.diag_cluster_dashboard_confirm
            })
            .setView(checks).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.diag_cluster_rcc_ivi_send, null).create()
        confirmation = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            assertion.setOnCheckedChangeListener { _, _ -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = assertion.isChecked && firmware.isChecked }
            firmware.setOnCheckedChangeListener { _, _ -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = assertion.isChecked && firmware.isChecked }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (assertion.isChecked && firmware.isChecked) { dialog.dismiss(); confirmation = null; begin(variant) }
            }
        }
        dialog.show()
    }
    private fun dashboardGeometry(): Boolean {
        val dm = app.getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val matches = dm.displays.filter { com.byd.dashcast.cluster.display.ClusterDisplayNames.isKnownClusterName(it.name) }
        if (matches.size != 1 || matches.single().state != android.view.Display.STATE_ON) return false
        val size = android.graphics.Point(); matches.single().getRealSize(size)
        return com.byd.dashcast.clusterlive.ClusterDashboardProfile.permitsGeometry(size.x, size.y,
            com.byd.dashcast.data.prefs.ClusterPrefs.isSmallClusterPanelLatched(app))
    }
    private fun chooseDashboardApp() {
        val expectedActivation = activation
        work.execute {
            val pm = app.packageManager
            val homes = pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }.toSet()
            val choices = pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != app.packageName && com.byd.dashcast.cluster.ProjectionSafetyPolicy.isAllowed(it.activityInfo.packageName, it.activityInfo.packageName in homes) }
                .distinctBy { it.activityInfo.packageName }.map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .sortedBy { it.second.lowercase(java.util.Locale.getDefault()) }
            handler.post {
                if (closed || !active || activation != expectedActivation) return@post
                AlertDialog.Builder(activity).setTitle(R.string.diag_cluster_dashboard_choose_app)
                    .setItems(choices.map { it.second }.toTypedArray()) { _, index ->
                        dashboardPackage = choices[index].first; dashboardLabel = choices[index].second
                        displayed?.let(::renderDashboard)
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
        }
    }
    private fun confirmDashboard(open: Boolean) {
        if (!open) { dashboardTicket = null; dashboardCommandGeneration.incrementAndGet() }
        if (displayed?.optString("variant") != ClusterThemeVariant.FULL_DASHBOARD_21F7735C.id || displayed?.optBoolean("rccDebt") != true) {
            dashboardMessage(R.string.diag_cluster_dashboard_prepare_first); return
        }
        if (open && (displayed?.optJSONObject("dashboard")?.optBoolean("selectionReady") != true ||
                displayed?.optString("phase") != "observation" || displayed?.optBoolean("uncertain") == true ||
                displayed?.optJSONObject("dashboard")?.optString("stage") !in setOf("NONE", "CLOSED_RECEIVED"))) {
            dashboardMessage(R.string.diag_cluster_dashboard_waiting); return
        }
        if (open && (dashboardPackage.isEmpty() || !dashboardGeometry())) {
            dashboardMessage(if (dashboardPackage.isEmpty()) R.string.diag_cluster_dashboard_choose_app else R.string.diag_cluster_dashboard_geometry); return
        }
        val expected = activation
        val checks = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val parked = CheckBox(activity).apply { text = activity.getString(R.string.diag_cluster_native_parked) }
        val firmware = CheckBox(activity).apply { text = activity.getString(R.string.diag_cluster_native_firmware) }
        checks.addView(parked); checks.addView(firmware)
        val dialog = AlertDialog.Builder(activity).setTitle(if (open) R.string.diag_cluster_dashboard_open else R.string.diag_cluster_dashboard_close)
            .setMessage(R.string.diag_cluster_dashboard_confirm).setView(checks)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.diag_cluster_native_send, null).create()
        confirmation = dialog
        dialog.setOnShowListener {
            val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE); send.isEnabled = false
            parked.setOnCheckedChangeListener { _, _ -> send.isEnabled = parked.isChecked && firmware.isChecked }
            firmware.setOnCheckedChangeListener { _, _ -> send.isEnabled = parked.isChecked && firmware.isChecked }
            send.setOnClickListener {
                if (!parked.isChecked || !firmware.isChecked || closed || activation != expected) return@setOnClickListener
                dialog.dismiss(); confirmation = null
                val request = {
                    val reserved = reserve()
                    if (reserved != null) {
                        val pinned = checkNotNull(session)
                        val expectedCommand = dashboardCommandGeneration.get()
                        val commandValidity = validity(reserved, foreground = true)
                        dashboardTicket = if (open) DashboardOpenTicket(expected, dashboardFilename, reserved, pinned) else null
                        submit(if (open) "dashboard-open" else "dashboard-close") {
                            check(active && !closed && activation == expected && dashboardCommandGeneration.get() == expectedCommand && pinned.isCurrent()) {
                                "Dashboard click expired before IPC; no sequence sent"
                            }
                            pinned.dashboardProjection(open, commandValidity)
                        }
                    } else dashboardMessage(R.string.diag_cluster_native_busy)
                }
                if (open) request() else stopDashboardSlots(request)
            }
        }
        dialog.show()
    }
    private fun stopDashboardSlots(next: () -> Unit) {
        dashboardTicket = null; dashboardCommandGeneration.incrementAndGet()
        // Remote recovery must never queue behind a blocked local VD transaction.
        next()
        val fission = dashboardFission
        fission?.stopAll(Runnable {
            if (fission.getSlots().isEmpty() && com.byd.dashcast.fission.FissionReleaseDebt.snapshot().isEmpty()) {
                dashboardSlotsUnsettled = false; dashboardUi.get()?.releaseReservation()
            }
        })
    }
    private fun ticketCurrent(ticket: DashboardOpenTicket): Boolean = dashboardTicket === ticket && !closed && active &&
        activation == ticket.activation && dashboardFilename == ticket.filename && ticket.pinned.isCurrent() &&
        ProjectionCommandBus.ownsDiagnostic(ticket.reservation) && dashboardLaunchStateReady
    private fun renderDashboard(j: JSONObject) {
        val own = j.optString("variant") == ClusterThemeVariant.FULL_DASHBOARD_21F7735C.id && j.optBoolean("rccDebt")
        val stage = j.optJSONObject("dashboard")?.optString("stage").orEmpty()
        dashboardFilename = j.optString("filename")
        dashboardLaunchStateReady = own && stage == "OPEN_RECEIVED" && !j.optBoolean("uncertain") &&
            !j.optBoolean("recoveryPending") && j.optJSONObject("dashboard")?.optBoolean("pending") == false
        val label = when {
            !own -> R.string.diag_cluster_dashboard_idle
            j.optBoolean("recoveryPending") -> R.string.diag_cluster_dashboard_restoring
            j.optBoolean("uncertain") || stage == "UNKNOWN" -> R.string.diag_cluster_dashboard_unknown
            stage == "OPEN_RECEIVED" && j.optBoolean("themeObservedByUser") && j.optJSONObject("dashboard")?.optString("pixels") == "USER_REPORTED" -> R.string.diag_cluster_dashboard_confirmed
            stage == "OPEN_RECEIVED" -> R.string.diag_cluster_dashboard_open_received
            stage == "CLOSED_RECEIVED" -> R.string.diag_cluster_dashboard_closed_received
            stage in setOf("OPENING", "CLOSING") || j.optString("phase") != "observation" -> R.string.diag_cluster_dashboard_waiting
            j.optJSONObject("dashboard")?.optBoolean("selectionReady") != true -> R.string.diag_cluster_dashboard_waiting
            else -> R.string.diag_cluster_dashboard_ready
        }
        dashboardStatus.text = activity.getString(label) + if (dashboardLabel.isNotEmpty()) "\n" + activity.getString(R.string.diag_cluster_dashboard_selected_app, dashboardLabel) else ""
        dashboardOpenButton?.isEnabled = own && j.optJSONObject("dashboard")?.optBoolean("selectionReady") == true &&
            j.optString("phase") == "observation" && !j.optBoolean("uncertain") && !j.optBoolean("recoveryPending") &&
            j.optJSONObject("dashboard")?.optBoolean("pending") == false && stage in setOf("NONE", "CLOSED_RECEIVED")
        dashboardObserveButton?.isEnabled = dashboardLaunchStateReady &&
            j.optJSONObject("dashboard")?.optString("round")?.matches(Regex("[0-9a-f]{32}")) == true
        if (!own || stage == "UNKNOWN" || j.optBoolean("uncertain") || j.optBoolean("recoveryPending")) dashboardTicket = null
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
                            if (slots.isEmpty() && com.byd.dashcast.fission.FissionReleaseDebt.snapshot().isEmpty()) {
                                dashboardSlotsUnsettled = false; dashboardUi.get()?.releaseReservation()
                            }
                        }
                        override fun onDaemonBinderAcquired(binder: android.os.IBinder?) {}
                        override fun onStatusMessage(message: String?) {}
                        override fun onSlotError(pkg: String?, message: String?) { dashboardUi.get()?.show("${message.orEmpty()}", "dashboard-app-slot") }
                        override fun onProjectionConflict(proceedCallback: Runnable?) { dashboardUi.get()?.show("Projection conflict", "dashboard-app-conflict") }
                    })
            }
            dashboardSlotsUnsettled = true
            val owner = java.lang.ref.WeakReference(this)
            runCatching { dashboardFission?.startDashboardSlot(dashboardPackage, dashboardLabel, reserved, ticket.pinned) { owner.get()?.ticketCurrent(ticket) == true } }
                .onFailure { show(ClusterThemeDiagnostics.failure(it), "dashboard-app-refused"); stopDashboardSlots {} }
        }
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
        if (!recoveryBusy.compareAndSet(false, true)) {
            recoveryMessage(activity.getString(R.string.diag_cluster_native_busy)); return
        }
        show(activity.getString(R.string.diag_cluster_rcc_restore_reading), "restore-status-pending")
        val requestedActivation = activation
        recovery.execute {
            var outcome: (() -> Unit)? = null
            try {
                check(com.byd.dashcast.proxy.ProxyClient.supportsProtocol(41)) {
                    activity.getString(R.string.diag_cluster_rcc_restore_protocol)
                }
                val pinned = ClusterThemeSession.pin(app).also { session = it }
                val text = pinned.status()
                val current = JSONObject(text)
                render(text, lease.get(), pinned, requestedActivation)
                outcome = {
                    if (!closed && active && activation == requestedActivation && pinned.isCurrent()) {
                        val local = ClusterThemeProjectionDebt.nonce(app)
                        val remoteName = current.optString("filename")
                        when {
                            current.optBoolean("recoveryPending") -> recoveryMessage(activity.getString(R.string.diag_cluster_rcc_restore_pending)) {
                                active && pinned.isCurrent() && displayed?.optBoolean("recoveryPending") == true
                            }
                            current.optBoolean("rccDebt") && current.optString("variant") == "UNKNOWN" -> recoveryMessage(activity.getString(R.string.diag_cluster_rcc_restore_mismatch))
                            current.optString("transport") == "android-ivi" && current.optBoolean("rccDebt") -> {
                                if (!ClusterThemePolicy.ownedName(remoteName) || local != null && remoteName != ClusterThemePolicy.PREFIX + local + ".rcc") {
                                    recoveryMessage(activity.getString(R.string.diag_cluster_rcc_restore_mismatch))
                                } else if (current.optBoolean("retirementAvailable")) showRecoveryGuidance(current, pinned, force = true)
                                else confirmIviRestore()
                            }
                            current.optString("transport") == "strict-renderer" && current.optBoolean("rccDebt") -> restore(false)
                            current.optBoolean("rccDebt") -> recoveryMessage(activity.getString(R.string.diag_cluster_rcc_restore_mismatch))
                            local != null && !current.optBoolean("rccDebt") -> confirmIviRestore()
                            else -> recoveryMessage(activity.getString(R.string.diag_cluster_rcc_restore_none))
                        }
                    }
                }
            } catch (error: Throwable) {
                show("Restore status unavailable: ${ClusterThemeDiagnostics.failure(error)}", "restore-status-failed")
                outcome = { if (!closed && activation == requestedActivation) recoveryMessage(activity.getString(R.string.diag_cluster_rcc_restore_failed) + "\n\n" + ClusterThemeDiagnostics.failure(error)) }
            } finally { recoveryBusy.set(false); handler.post { outcome?.invoke(); refreshPrompt() } }
        }
    }
    private fun recoveryMessage(message: String, current: (() -> Boolean)? = null) {
        if (closed || activity.isFinishing || activity.isDestroyed) return
        recoveryGuidance?.dismiss()
        recoveryGuidanceCurrent = current
        recoveryGuidance = AlertDialog.Builder(activity).setTitle(R.string.diag_cluster_rcc_restore_result)
            .setMessage(message).setPositiveButton(android.R.string.ok, null).show()
        ClusterThemeDiagnostics.local(app, "restore-guidance", "LOCAL", message)
    }
    private fun showBusy() {
        show("Another request/diagnostic is active. Restore remains available.", "start-busy")
        if (displayed?.optBoolean("rccDebt") == true || ClusterThemeProjectionDebt.blocked(app))
            recoveryMessage(activity.getString(R.string.diag_cluster_rcc_restore_blocked))
    }
    private fun showRecoveryGuidance(j: JSONObject, pinned: ClusterThemeSession, force: Boolean = false) {
        if (recoveryGuidanceCurrent?.invoke() == false) { recoveryGuidance?.dismiss(); recoveryGuidance = null; recoveryGuidanceCurrent = null }
        if (!closed && active && pinned.isCurrent() && j.optBoolean("rccDebt") && j.optString("transport") == "android-ivi" &&
            !com.byd.dashcast.proxy.ProxyClient.supportsProtocol(41)) {
            if (shownRecoveryGuidance.add("protocol41-required")) recoveryMessage(activity.getString(R.string.diag_cluster_rcc_restore_protocol))
            return
        }
        if (closed || !active || !pinned.isCurrent() || recoveryBusy.get() || j.optBoolean("recoveryPending") || confirmation?.isShowing == true ||
            !j.optBoolean("rccDebt") || j.optBoolean("defaultQueried") || j.optString("transport") != "android-ivi") return
        val eligible = j.optBoolean("retirementAvailable")
        val retired = j.optJSONObject("retirement")?.optBoolean("moved") == true
        val failed = j.optString("state") == "QUARANTINE" && j.optString("phase") in setOf("restore-query", "retirement-preflight", "retired-read-only-query", "carrier-retired")
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
            mismatch -> activity.getString(R.string.diag_cluster_rcc_restore_mismatch)
            eligible -> activity.getString(R.string.diag_cluster_rcc_restore_same)
            retired -> activity.getString(R.string.diag_cluster_rcc_retired_waiting)
            else -> activity.getString(R.string.diag_cluster_rcc_restore_failed)
        } + "\n\n" + activity.getString(R.string.diag_cluster_rcc_restore_blocked) + if (failed) "\n\n" + j.optString("detail") else ""
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.diag_cluster_rcc_restore_result).setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (prepare) R.string.diag_cluster_rcc_retire_button else android.R.string.ok, null).create()
        recoveryGuidance = dialog; recoveryGuidanceKey = key; recoveryGuidanceCurrent = current
        shownRecoveryGuidance.add(key); while (shownRecoveryGuidance.size > 16) shownRecoveryGuidance.remove(shownRecoveryGuidance.first())
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (recoveryGuidance !== dialog || !current()) {
                    dialog.dismiss(); ClusterThemeDiagnostics.local(app, "restore-guidance", "REFUSED", "Expired/replaced recovery guidance; no action sent")
                    return@setOnClickListener
                }
                dialog.dismiss()
                ClusterThemeDiagnostics.local(app, "restore-guidance", "LOCAL", "${if (prepare) "prepare-confirmation-requested" else "acknowledged"}; no OEM command or retirement sent by guidance")
                if (prepare) confirmIviRestore(retire = true)
            }
        }
        dialog.setOnCancelListener { ClusterThemeDiagnostics.local(app, "restore-guidance", "LOCAL", "cancel; recovery debt unchanged; no action sent") }
        dialog.setOnDismissListener { if (recoveryGuidance === dialog) { recoveryGuidance = null; recoveryGuidanceCurrent = null } }
        ClusterThemeDiagnostics.local(app, "restore-guidance", "LOCAL", "shown; eligible=$eligible retired=$retired state=${j.optString("state")} filename=$name; no automatic recovery/retirement/observation")
        dialog.show()
    }
    private fun confirmIviRestore(retire: Boolean = false, verifyCorners: Boolean = false) {
        recoveryGuidance?.dismiss(); recoveryGuidance = null; recoveryGuidanceCurrent = null
        val expectedActivation = activation
        val expectedSession = displayedSession
        val expectedFilename = displayed?.optString("filename")
        val checks = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val parked = CheckBox(activity).apply { text = activity.getString(R.string.diag_cluster_native_parked) }
        val firmware = CheckBox(activity).apply { text = activity.getString(R.string.diag_cluster_native_firmware) }
        checks.addView(parked); checks.addView(firmware)
        val retired = displayed?.let { it.optBoolean("rccDebt") && it.optJSONObject("retirement")?.optBoolean("moved") == true } == true
        val dialog = AlertDialog.Builder(activity).setTitle(if (verifyCorners) R.string.diag_cluster_rcc_corners_verify_button else if (retire) R.string.diag_cluster_rcc_retire_button
            else if (retired) R.string.diag_cluster_rcc_verify_return else R.string.diag_cluster_rcc_restore)
            .setMessage(if (verifyCorners) R.string.diag_cluster_rcc_corners_verify_confirm else if (retire) R.string.diag_cluster_rcc_retire_confirm else if (retired) R.string.diag_cluster_rcc_retired_waiting
                else if (displayed?.optString("variant") == ClusterThemeVariant.FULL_DASHBOARD_21F7735C.id) R.string.diag_cluster_dashboard_restore_confirm
                else R.string.diag_cluster_rcc_ivi_restore_confirm).setView(checks)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.diag_cluster_native_send, null).create()
        confirmation = dialog
        dialog.setOnShowListener {
            val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE); send.isEnabled = false
            parked.setOnCheckedChangeListener { _, _ -> send.isEnabled = parked.isChecked && firmware.isChecked }
            firmware.setOnCheckedChangeListener { _, _ -> send.isEnabled = parked.isChecked && firmware.isChecked }
            send.setOnClickListener { if (parked.isChecked && firmware.isChecked) {
                dialog.dismiss(); confirmation = null
                if (closed || activation != expectedActivation || expectedSession != null && (!expectedSession.isCurrent() || displayedSession?.sharesConnection(expectedSession) != true || displayed?.optString("filename") != expectedFilename) ||
                    retire && displayed?.let { it.optBoolean("retirementAvailable") || it.optJSONObject("retirement")?.optBoolean("moved") == false } != true) {
                    recoveryMessage(activity.getString(R.string.diag_cluster_rcc_restore_expired))
                } else restore(true, retire, verifyCorners)
            } }
        }
        dialog.show()
    }
    private fun restore(ivi: Boolean, retire: Boolean = false, verifyCorners: Boolean = false) {
        if (!recoveryBusy.compareAndSet(false, true)) { show(activity.getString(R.string.diag_cluster_native_busy)); return }
        if (!verifyCorners) shownRecoveryGuidance.clear()
        val reserved = if (ivi) reserve() else null
        if (ivi && reserved == null) {
            recoveryBusy.set(false)
            val message = activity.getString(R.string.diag_cluster_native_busy)
            show(message, "restore-reservation-refused"); recoveryMessage(message); return
        }
        displayed?.let { j -> displayedSession?.let { rememberReservationDebt(j, it) } }
        val requestedLease = lease.get(); val requestedActivation = activation
        recovery.execute {
            try {
                val s = session ?: ClusterThemeSession.pin(app).also { session = it }
                render(if (verifyCorners) s.verifyCorners(validity(checkNotNull(reserved))) else if (ivi) s.restoreIvi(validity(checkNotNull(reserved)), retire) else s.restore(), requestedLease, s, requestedActivation)
            } catch (error: Throwable) {
                val failure = ClusterThemeDiagnostics.failure(error)
                show("${if (verifyCorners) "Read-only verification unavailable" else "Restore UNKNOWN/refused"}: $failure. No reconnect/replay.", if (verifyCorners) "corner-verification" else "restore")
                handler.post { if (!closed && activation == requestedActivation) recoveryMessage(activity.getString(R.string.diag_cluster_rcc_restore_failed) + "\n\n" + failure) }
            }
            finally { recoveryBusy.set(false); releaseReservation(); handler.post { refreshPrompt(); displayed?.let { j -> displayedSession?.let { showRecoveryGuidance(j, it) } } } }
        }
    }
    private fun begin(variant: ClusterThemeVariant) {
        if (Build.VERSION.SDK_INT != 29 || !Platform.get().isAutoDetectedDiLink3()) {
            show(activity.getString(R.string.diag_cluster_rcc_ivi_platform), "ivi-platform-refused"); return
        }
        val reserved = reserve()
        if (reserved == null) { show(activity.getString(R.string.diag_cluster_native_busy), "ivi-reservation-refused"); return }
        if (variant.isDashboard && !dashboardGeometry()) {
            releaseReservation(); dashboardMessage(R.string.diag_cluster_dashboard_geometry); return
        }
        // Reserve request lane BEFORE global busy ownership. Polling cannot strand the claim.
        if (!busy.compareAndSet(false, true)) { releaseReservation(); show("Another request is active. No start submitted.", "start-busy"); return }
        if (recoveryBusy.get() || diagClaimed.get() || !claimDiag()) { busy.set(false); releaseReservation(); show("Diagnostic lane unavailable. No start submitted.", "start-refused"); return }
        diagClaimed.set(true); val myLease = lease.incrementAndGet(); val requestedActivation = activation; abortBeforeSend.set(false)
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
            } finally { busy.set(false); handler.post { refreshPrompt() } }
        }
        pendingNotice()
    }
    private fun start(variant: ClusterThemeVariant, reserved: ProjectionCommandSequencer.Session, beforeTransaction: () -> Unit): String {
        val s = session ?: ClusterThemeSession.pin(app).also { session = it }
        check(com.byd.dashcast.proxy.ProxyClient.supportsProtocol(41)) { activity.getString(R.string.diag_cluster_rcc_restore_protocol) }
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
                    }
                }
                show("REFUSED/UNKNOWN: ${ClusterThemeDiagnostics.failure(t)}. No request replayed.", "$operation-failed")
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
        rememberReservationDebt(j, pinned)
        if (j.getString("state") in setOf("IDLE", "CLEAN", "REFUSED")) releaseClaim(expectedLease)
        val query = j.optJSONObject("oemQuery")
        queryPending = query?.optString("state") == "PENDING"
        val queryText = if (query == null || query.length() == 0) "" else activity.getString(R.string.diag_cluster_oem_query_result,
            query.optString("state"), query.optString("detail"), query.opt("registration")?.toString() ?: "UNKNOWN",
            query.opt("native")?.toString() ?: "UNKNOWN", query.optString("selection")) + "\n\n"
        val retirement = j.optJSONObject("retirement")
        val retiredPending = j.optBoolean("rccDebt") && retirement?.optBoolean("moved") == true
        val guided = if (retiredPending && !j.optBoolean("defaultQueried")) activity.getString(R.string.diag_cluster_rcc_retired_waiting) + "\n\n" else ""
        val evidenceText = j.optJSONObject("loadEvidence")?.let { activity.getString(R.string.diag_cluster_rcc_load_receipts,
            it.optString("send", "UNKNOWN"), it.optString("selection", "UNKNOWN"), it.optString("ack2", "UNKNOWN"), it.optString("pixels", "UNKNOWN")) + "\n\n" }.orEmpty()
        val cornerGuide = if (ClusterThemeVariant.isCornerId(j.optString("variant")) && j.optBoolean("rccDebt") && !j.optBoolean("defaultQueried") && retirement == null) {
            (if (j.optString("state") == "SELECTION_PENDING_OEM_APPLY" && j.optString("phase") == "observation" &&
                !j.optBoolean("uncertain", true) && j.optJSONObject("loadEvidence")?.optString("selection") == "OWN_PATH_REPORTED")
                activity.getString(R.string.diag_cluster_rcc_corners_pending) + "\n" else "") +
                activity.getString(R.string.diag_cluster_rcc_corners_verify_confirm) + "\n\n"
        } else ""
        val blocked = if (j.optBoolean("rccDebt")) activity.getString(R.string.diag_cluster_rcc_restore_blocked) + "\n\n" else ""
        val pending = if (j.optBoolean("recoveryPending")) activity.getString(R.string.diag_cluster_rcc_restore_pending) + "\n\n" else ""
        val summary = blocked + pending + guided + cornerGuide + evidenceText + queryText + "${j.getString("state")}: ${j.getString("detail")}\nProfile ${j.optString("variant", "legacy marker")}; transport=${j.optString("transport", "legacy strict renderer")}; native identity=${j.optString("nativeIdentity", "UNKNOWN")}; phase ${j.optString("phase", "unrecorded")}; daemon UID ${j.getInt("daemonUid")}; default queried=${j.getBoolean("defaultQueried")}; marker/theme/OEM observed by user=${j.getBoolean("markerObservedByUser")}/${j.optBoolean("themeObservedByUser")}/${j.getBoolean("oemObservedByUser")}"
        handler.post {
            if (!closed && pinned.isCurrent()) {
                if (active && activation == requestedActivation) {
                    displayed = j; displayedSession = pinned
                    renderDashboard(j)
                    restoreButton.text = activity.getString(if (retiredPending) R.string.diag_cluster_rcc_verify_return else if (j.optBoolean("retirementAvailable")) R.string.diag_cluster_rcc_retire_button else R.string.diag_cluster_rcc_restore)
                    restoreButton.contentDescription = restoreButton.text
                    retireButton.visibility = if (j.optBoolean("rccDebt") && (j.optBoolean("retirementAvailable") || retirement != null && !retirement.optBoolean("moved"))) android.view.View.VISIBLE else android.view.View.GONE
                    cornerVerifyButton.visibility = if (j.optBoolean("rccDebt") && j.optBoolean("mutated") && ClusterThemeVariant.isCornerId(j.optString("variant")) && retirement == null) android.view.View.VISIBLE else android.view.View.GONE
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
    private fun show(text: String, operation: String? = null) {
        if (operation != null) ClusterThemeDiagnostics.local(app, operation, "LOCAL", text)
        handler.post { if (!closed && !activity.isDestroyed) status.text = text }
        if (operation != null && (operation.startsWith("dashboard") || operation.endsWith("failed") || operation.endsWith("refused") || operation.contains("busy"))) {
            handler.post { if (!closed && active) dashboardStatus.text = activity.getString(
                if (operation.contains("busy")) R.string.diag_cluster_native_busy else R.string.diag_cluster_dashboard_unknown) }
        }
    }
    private fun dashboardMessage(label: Int) {
        val text = activity.getString(label)
        show(text)
        dashboardStatus.text = text
    }
    fun setActive(value: Boolean) {
        if (!value) { dashboardTicket = null; dashboardCommandGeneration.incrementAndGet() }
        active = value
        activation++
        displayed = null; displayedSession = null
        handler.removeCallbacks(poll)
        if (!value) {
            confirmation?.dismiss(); confirmation = null
            recoveryGuidance?.dismiss(); recoveryGuidance = null; recoveryGuidanceCurrent = null
            displayed = null; displayedSession = null
            prompts.update("rcc", null); prompts.update("blocked", null)
        } else {
            // Only status from an already connected daemon; no OEM query, connect or replay.
            submit("status") { ClusterThemeSession.pin(app).also { session = it }.status() }
            handler.postDelayed(poll, 1500)
        }
    }
    private fun rccKey(j: JSONObject): String = listOf("filename", "variant", "transport", "phase", "state", "mutated", "defaultQueried").joinToString(":") { (j.opt(it) ?: JSONObject.NULL).toString() } +
        ":${j.optJSONObject("cornerVerification")?.optString("round")}:${j.optJSONObject("cornerVerification")?.optBoolean("available") }"
    private fun refreshPrompt() {
        val j = displayed
        val s = displayedSession
        if (!active || closed || busy.get() && !statusPending || observationPending.get() || recoveryBusy.get() || j == null || s == null || !s.isCurrent() ||
            j.optString("transport") == "android-ivi" && !com.byd.dashcast.proxy.ProxyClient.supportsProtocol(41) ||
            j.optBoolean("recoveryPending") || !j.optBoolean("mutated") || !ClusterThemePolicy.ownedName(j.optString("filename"))) {
            prompts.update("rcc", null); return
        }
        val restoring = j.optBoolean("defaultQueried") && !j.optBoolean("oemObservedByUser") && j.optString("state") in setOf("DEFAULT_QUERIED", "QUARANTINE")
        val variant = j.optString("variant")
        val loading = !j.optBoolean("defaultQueried") && when (variant) {
            "corner-marker", "corner-marker-21f7735c" -> j.optJSONObject("cornerVerification")?.optBoolean("available") == true && !j.optBoolean("markerObservedByUser")
            "marker", "full-surface-test" -> j.optString("state") == "AWAITING_OBSERVATION" && !j.optBoolean(if (variant == "marker") "markerObservedByUser" else "themeObservedByUser")
            else -> false
        }
        if (!restoring && !loading) { prompts.update("rcc", null); return }
        val snapshot = rccKey(j)
        val filename = j.optString("filename")
        prompts.update("rcc", ClusterObservationPrompts.Question("rcc:$snapshot", if (restoring) 90 else 50, s,
            if (restoring) R.string.diag_cluster_observe_oem_title else R.string.diag_cluster_observe_rcc_title,
            when { restoring -> R.string.diag_cluster_observe_oem_message; variant == "marker" -> R.string.diag_cluster_observe_marker_message; ClusterThemeVariant.isCornerId(variant) -> R.string.diag_cluster_observe_corners_message; else -> R.string.diag_cluster_observe_theme_message },
            R.string.diag_cluster_observation_yes,
            current = { active && !closed && (!busy.get() || statusPending) && !observationPending.get() && !recoveryBusy.get() && displayedSession === s && displayed?.let(::rccKey) == snapshot },
            answer = {
                prompts.update("rcc", null)
                val expectedActivation = activation
                observe(if (restoring) "observe-oem" else "observe-rcc") {
                    check(session === s && s.isCurrent()) { "Visual confirmation belongs to a replaced session" }
                    val current = JSONObject(s.status())
                    check(active && !closed && activation == expectedActivation && rccKey(current) == snapshot) { "Visual confirmation expired; inspect the current cluster again" }
                    when { restoring -> s.observedOem(filename); variant == "marker" -> s.observedMarker(filename); ClusterThemeVariant.isCornerId(variant) -> s.observedCorners(filename, current.getJSONObject("cornerVerification").getString("round")); else -> s.observedFullSurface(filename) }
                }
            }))
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
                session?.let { render(text, expectedLease, it, requestedActivation) }
            } catch (t: Throwable) { show("Visual confirmation refused: ${ClusterThemeDiagnostics.failure(t)}. No load/replay.", "$operation-failed") }
            finally { if (acquired) busy.set(false); observationPending.set(false); handler.post { refreshPrompt() } }
        }
    }
    fun close() { setActive(false); closed = true; confirmation?.dismiss(); confirmation = null; handler.removeCallbacksAndMessages(null) }
}
