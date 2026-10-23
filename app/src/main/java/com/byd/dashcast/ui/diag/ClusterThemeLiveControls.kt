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
    private val nativeStatusUnavailable: () -> Unit = {}) {
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
    }
    private val handler = Handler(Looper.getMainLooper())
    private val app = activity.applicationContext
    @Volatile private var closed = false
    @Volatile private var session: ClusterThemeSession? = null
    private var confirmation: AlertDialog? = null
    private var lastSummary = ""
    @Volatile private var active = false
    @Volatile private var activation = 0L
    @Volatile private var statusPending = false
    private val observationPending = AtomicBoolean(false)
    private var displayed: JSONObject? = null
    private var displayedSession: ClusterThemeSession? = null
    private val restoreButton = Button(activity)
    private val retireButton = Button(activity)
    private val cornerVerifyButton = Button(activity)
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
                val shown = displayed
                if (shown?.optString("transport") == "strict-renderer" && shown.optString("state") !in setOf("IDLE", "CLEAN", "REFUSED")) restore(false)
                else if (shown?.optString("transport") == "android-ivi" || ClusterThemeProjectionDebt.blocked(app)) confirmIviRestore()
                else restore(false)
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
                if (!queryPending && !observationPending.get() && !busy.get() && !recoveryBusy.get() && !otherBusy() && !diagClaimed.get()) requestStart(ClusterThemeVariant.CORNER_MARKER)
                else show("Another request/diagnostic is active. Restore remains available.", "start-busy")
            }
        })
        controls.addView(cornerVerifyButton.apply {
            text = activity.getString(R.string.diag_cluster_rcc_corners_verify_button)
            isAllCaps = false; minHeight = (48 * activity.resources.displayMetrics.density).toInt()
            visibility = android.view.View.GONE
            setOnClickListener { confirmation?.dismiss(); confirmIviRestore(verifyCorners = true) }
        })
        controls.addView(Button(activity).apply {
            text = activity.getString(R.string.diag_cluster_rcc_marker_button)
            isAllCaps = false; minHeight = (48 * activity.resources.displayMetrics.density).toInt()
            setOnClickListener {
                if (!queryPending && !observationPending.get() && !busy.get() && !recoveryBusy.get() && !otherBusy() && !diagClaimed.get()) requestStart(ClusterThemeVariant.MARKER)
                else show("Another request/diagnostic is active. Restore remains available.", "start-busy")
            }
        })
        controls.addView(Button(activity).apply {
            text = activity.getString(R.string.diag_cluster_rcc_theme_button)
            isAllCaps = false; minHeight = (48 * activity.resources.displayMetrics.density).toInt()
            setOnClickListener {
                if (!queryPending && !observationPending.get() && !busy.get() && !recoveryBusy.get() && !otherBusy() && !diagClaimed.get()) requestStart(ClusterThemeVariant.FULL_SURFACE_TEST)
                else show("Another request/diagnostic is active. Restore remains available.", "start-busy")
            }
        })
        controls.addView(TextView(activity).apply {
            text = activity.getString(R.string.diag_cluster_rcc_ivi_help)
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
                ClusterThemeVariant.CORNER_MARKER -> R.string.diag_cluster_rcc_corners_confirm
                ClusterThemeVariant.MARKER -> R.string.diag_cluster_rcc_ivi_marker_confirm
                ClusterThemeVariant.FULL_SURFACE_TEST -> R.string.diag_cluster_rcc_ivi_theme_confirm
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
    private fun reserve(): ProjectionCommandSequencer.Session? = synchronized(reservationLock) {
        reservation?.takeIf { ProjectionCommandBus.isCurrent(it) }
            ?: ProjectionCommandBus.reserveDiagnostic()?.also { reservation = it }
    }
    private fun validity(lease: ProjectionCommandSequencer.Session) = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != 1) return false
            data.enforceInterface("com.byd.dashcast.RccReservation")
            reply?.writeNoException(); reply?.writeInt(if (ProjectionCommandBus.isCurrent(lease)) 1 else 0)
            return true
        }
    }
    private fun releaseReservation() = synchronized(reservationLock) {
        if (!ClusterThemeProjectionDebt.blocked(app)) reservation?.let { ProjectionCommandBus.endSession(it); reservation = null }
    }
    private fun confirmIviRestore(retire: Boolean = false, verifyCorners: Boolean = false) {
        val checks = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val parked = CheckBox(activity).apply { text = activity.getString(R.string.diag_cluster_native_parked) }
        val firmware = CheckBox(activity).apply { text = activity.getString(R.string.diag_cluster_native_firmware) }
        checks.addView(parked); checks.addView(firmware)
        val retired = displayed?.let { it.optBoolean("rccDebt") && it.optJSONObject("retirement")?.optBoolean("moved") == true } == true
        val dialog = AlertDialog.Builder(activity).setTitle(if (verifyCorners) R.string.diag_cluster_rcc_corners_verify_button else if (retire) R.string.diag_cluster_rcc_retire_button
            else if (retired) R.string.diag_cluster_rcc_verify_return else R.string.diag_cluster_rcc_restore)
            .setMessage(if (verifyCorners) R.string.diag_cluster_rcc_corners_verify_confirm else if (retire) R.string.diag_cluster_rcc_retire_confirm else if (retired) R.string.diag_cluster_rcc_retired_waiting
                else R.string.diag_cluster_rcc_ivi_restore_confirm).setView(checks)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.diag_cluster_native_send, null).create()
        confirmation = dialog
        dialog.setOnShowListener {
            val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE); send.isEnabled = false
            parked.setOnCheckedChangeListener { _, _ -> send.isEnabled = parked.isChecked && firmware.isChecked }
            firmware.setOnCheckedChangeListener { _, _ -> send.isEnabled = parked.isChecked && firmware.isChecked }
            send.setOnClickListener { if (parked.isChecked && firmware.isChecked) { dialog.dismiss(); confirmation = null; restore(true, retire, verifyCorners) } }
        }
        dialog.show()
    }
    private fun restore(ivi: Boolean, retire: Boolean = false, verifyCorners: Boolean = false) {
        if (!recoveryBusy.compareAndSet(false, true)) { show(activity.getString(R.string.diag_cluster_native_busy)); return }
        val reserved = if (ivi) reserve() else null
        if (ivi && reserved == null) { recoveryBusy.set(false); show(activity.getString(R.string.diag_cluster_native_busy)); return }
        val requestedLease = lease.get(); val requestedActivation = activation
        recovery.execute {
            try {
                val s = session ?: ClusterThemeSession.pin(app).also { session = it }
                render(if (verifyCorners) s.verifyCorners(validity(checkNotNull(reserved))) else if (ivi) s.restoreIvi(validity(checkNotNull(reserved)), retire) else s.restore(), requestedLease, s, requestedActivation)
            } catch (error: Throwable) { show("${if (verifyCorners) "Read-only verification unavailable" else "Restore UNKNOWN/refused"}: ${ClusterThemeDiagnostics.failure(error)}. No reconnect/replay.", if (verifyCorners) "corner-verification" else "restore") }
            finally { recoveryBusy.set(false); releaseReservation(); handler.post { refreshPrompt() } }
        }
    }
    private fun begin(variant: ClusterThemeVariant) {
        if (Build.VERSION.SDK_INT != 29 || !Platform.get().isAutoDetectedDiLink3()) {
            show(activity.getString(R.string.diag_cluster_rcc_ivi_platform), "ivi-platform-refused"); return
        }
        val reserved = reserve()
        if (reserved == null) { show(activity.getString(R.string.diag_cluster_native_busy), "ivi-reservation-refused"); return }
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
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { s.startIvi(it, variant, validity(reserved), beforeTransaction) }
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
        val cornerGuide = if (j.optString("variant") == "corner-marker" && j.optBoolean("rccDebt") && !j.optBoolean("defaultQueried") && retirement == null) {
            (if (j.optString("state") == "SELECTION_PENDING_OEM_APPLY" && j.optString("phase") == "observation" &&
                !j.optBoolean("uncertain", true) && j.optJSONObject("loadEvidence")?.optString("selection") == "OWN_PATH_REPORTED")
                activity.getString(R.string.diag_cluster_rcc_corners_pending) + "\n" else "") +
                activity.getString(R.string.diag_cluster_rcc_corners_verify_confirm) + "\n\n"
        } else ""
        val summary = guided + cornerGuide + evidenceText + queryText + "${j.getString("state")}: ${j.getString("detail")}\nProfile ${j.optString("variant", "legacy marker")}; transport=${j.optString("transport", "legacy strict renderer")}; native identity=${j.optString("nativeIdentity", "UNKNOWN")}; phase ${j.optString("phase", "unrecorded")}; daemon UID ${j.getInt("daemonUid")}; default queried=${j.getBoolean("defaultQueried")}; marker/theme/OEM observed by user=${j.getBoolean("markerObservedByUser")}/${j.optBoolean("themeObservedByUser")}/${j.getBoolean("oemObservedByUser")}"
        handler.post {
            if (!closed && pinned.isCurrent()) {
                if (active && activation == requestedActivation) {
                    displayed = j; displayedSession = pinned
                    restoreButton.text = activity.getString(if (retiredPending) R.string.diag_cluster_rcc_verify_return else R.string.diag_cluster_rcc_restore)
                    restoreButton.contentDescription = restoreButton.text
                    retireButton.visibility = if (j.optBoolean("rccDebt") && (j.optBoolean("retirementAvailable") || retirement != null && !retirement.optBoolean("moved"))) android.view.View.VISIBLE else android.view.View.GONE
                    cornerVerifyButton.visibility = if (j.optBoolean("rccDebt") && j.optBoolean("mutated") && j.optString("variant") == "corner-marker" && retirement == null) android.view.View.VISIBLE else android.view.View.GONE
                    if (j.has("nativeMode")) nativeStatus(text, pinned)
                    refreshPrompt()
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
    }
    fun setActive(value: Boolean) {
        active = value
        activation++
        displayed = null; displayedSession = null
        handler.removeCallbacks(poll)
        if (!value) {
            confirmation?.dismiss(); confirmation = null
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
            !j.optBoolean("mutated") || !ClusterThemePolicy.ownedName(j.optString("filename"))) {
            prompts.update("rcc", null); return
        }
        val restoring = j.optBoolean("defaultQueried") && !j.optBoolean("oemObservedByUser") && j.optString("state") in setOf("DEFAULT_QUERIED", "QUARANTINE")
        val variant = j.optString("variant")
        val loading = !j.optBoolean("defaultQueried") && when (variant) {
            "corner-marker" -> j.optJSONObject("cornerVerification")?.optBoolean("available") == true && !j.optBoolean("markerObservedByUser")
            "marker", "full-surface-test" -> j.optString("state") == "AWAITING_OBSERVATION" && !j.optBoolean(if (variant == "marker") "markerObservedByUser" else "themeObservedByUser")
            else -> false
        }
        if (!restoring && !loading) { prompts.update("rcc", null); return }
        val snapshot = rccKey(j)
        val filename = j.optString("filename")
        prompts.update("rcc", ClusterObservationPrompts.Question("rcc:$snapshot", if (restoring) 90 else 50, s,
            if (restoring) R.string.diag_cluster_observe_oem_title else R.string.diag_cluster_observe_rcc_title,
            when { restoring -> R.string.diag_cluster_observe_oem_message; variant == "marker" -> R.string.diag_cluster_observe_marker_message; variant == "corner-marker" -> R.string.diag_cluster_observe_corners_message; else -> R.string.diag_cluster_observe_theme_message },
            R.string.diag_cluster_observation_yes,
            current = { active && !closed && (!busy.get() || statusPending) && !observationPending.get() && !recoveryBusy.get() && displayedSession === s && displayed?.let(::rccKey) == snapshot },
            answer = {
                prompts.update("rcc", null)
                val expectedActivation = activation
                observe(if (restoring) "observe-oem" else "observe-rcc") {
                    check(session === s && s.isCurrent()) { "Visual confirmation belongs to a replaced session" }
                    val current = JSONObject(s.status())
                    check(active && !closed && activation == expectedActivation && rccKey(current) == snapshot) { "Visual confirmation expired; inspect the current cluster again" }
                    when { restoring -> s.observedOem(filename); variant == "marker" -> s.observedMarker(filename); variant == "corner-marker" -> s.observedCorners(filename, current.getJSONObject("cornerVerification").getString("round")); else -> s.observedFullSurface(filename) }
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
