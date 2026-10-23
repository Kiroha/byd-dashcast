package com.byd.dashcast.ui.diag

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.byd.dashcast.clusterlive.ClusterNativeProjectionDebt
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
        header.addView(Button(activity).apply {
            text = activity.getString(R.string.diag_cluster_rcc_restore)
            contentDescription = activity.getString(R.string.diag_cluster_rcc_restore)
            setOnClickListener {
                confirmation?.dismiss(); confirmation = null
                abortBeforeSend.set(true)
                if (recoveryBusy.compareAndSet(false, true)) {
                    val requestedLease = lease.get()
                    val requestedActivation = activation
                    recovery.execute {
                        try {
                            val s = session ?: ClusterThemeSession.pin(app).also { session = it }
                            render(s.restore(), requestedLease, s, requestedActivation)
                        } catch (t: Throwable) { show("Restore UNKNOWN/refused: ${ClusterThemeDiagnostics.failure(t)}. No reconnect/replay. Read current connected daemon status for recovery.", "restore") }
                        finally { recoveryBusy.set(false); handler.post { refreshPrompt() } }
                    }
                    handler.postDelayed({ if (recoveryBusy.get()) show("Restore pending: remote completion UNKNOWN. No second request is replayed.", "restore-pending") }, 3000)
                } else show("Restore already pending; no second request replayed.", "restore-busy")
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
            text = "Change physical cluster — passive TEST marker (experimental)"
            setOnClickListener {
                if (!queryPending && !observationPending.get() && !busy.get() && !recoveryBusy.get() && !otherBusy() && !diagClaimed.get()) requestStart(ClusterThemeVariant.MARKER)
                else show("Another request/diagnostic is active. Restore remains available.", "start-busy")
            }
        })
        controls.addView(Button(activity).apply {
            text = activity.getString(R.string.diag_cluster_rcc_theme_button)
            setOnClickListener {
                if (!queryPending && !observationPending.get() && !busy.get() && !recoveryBusy.get() && !otherBusy() && !diagClaimed.get()) requestStart(ClusterThemeVariant.FULL_SURFACE_TEST)
                else show("Another request/diagnostic is active. Restore remains available.", "start-busy")
            }
        })
        controls.addView(TextView(activity).apply {
            text = "Theme test: manually select Full navigation and night mode first. Native dark background with cyan frame replaces OEM band decoration; real OEM information stays. Other modes keep the stock appearance. The frame follows the OEM root geometry; it does not establish exclusive physical screen control. Requires the explicitly connected protocol-32 daemon."
            textSize = 14f
        })
        controls.addView(TextView(activity).apply {
            text = "Actual OEM root reload; original QML code retained, visual preservation unvalidated. Primary transport: explicitly connected ProxyDaemon/helper at its existing UID (normally shell 2000), subject to OEM admission. Firmware 13.1.33.2602030.1 only. Restore requested after 30 seconds; reboot alone is not assured rollback."
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
        } else confirm(variant)
    }
    private fun showNativeBlock() {
        ClusterThemeDiagnostics.local(app, "rcc-start-blocked", "REFUSED", "Native mode recovery/visual normal confirmation remains pending; no RCC load submitted. Return 18 is manual; answering the observation never starts RCC.")
        show(activity.getString(R.string.diag_cluster_rcc_blocked_message))
        prompts.explainBlocked()
    }
    private fun confirm(variant: ClusterThemeVariant) {
        val assertion = CheckBox(activity).apply { text = "I assert the vehicle is parked in a suitable test location." }
        val message = if (variant == ClusterThemeVariant.MARKER)
            "This requests a real OEM UI reload using the connected ProxyDaemon. Original instrument/alert QML code is retained with a passive marker; physical preservation and recovery are unvalidated. Keep the central display available. Missing marker is inconclusive. Reboot is not assured rollback. Movement is not detected."
        else "Select Full navigation and night mode manually before testing. This loads a native dark QML background with cyan frame behind the original instruments and alerts, including the top and bottom band areas. Other modes retain the original appearance. No Full/night switch is forced. Physical legibility and recovery are unvalidated; keep the central display available. Restore is requested after 30 seconds. Reboot is not assured rollback. Movement is not detected."
        val dialog = AlertDialog.Builder(activity).setTitle("Change the physical instrument cluster")
            .setMessage(message)
            .setView(assertion).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (variant == ClusterThemeVariant.MARKER) "Load marker" else "Load test theme", null).create()
        confirmation = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            assertion.setOnCheckedChangeListener { _, checked -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = checked }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (assertion.isChecked) { dialog.dismiss(); confirmation = null; begin(variant) }
            }
        }
        dialog.show()
    }
    private fun begin(variant: ClusterThemeVariant) {
        // Reserve request lane BEFORE global busy ownership. Polling cannot strand the claim.
        if (!busy.compareAndSet(false, true)) { show("Another request is active. No start submitted.", "start-busy"); return }
        if (recoveryBusy.get() || diagClaimed.get() || !claimDiag()) { busy.set(false); show("Diagnostic lane unavailable. No start submitted.", "start-refused"); return }
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
                } else render(start(variant) { check(!closed && activation == requestedActivation && !abortBeforeSend.get()) { "Cancelled before start transmission" }; ipcBegan = true }, myLease, s, requestedActivation)
            }
            catch (t: Throwable) {
                if (!ipcBegan) releaseClaim(myLease)
                show(if (ipcBegan) "UNKNOWN after start transmission: ${ClusterThemeDiagnostics.failure(t)}. No replay; use Restore."
                    else "REFUSED before start transmission: ${ClusterThemeDiagnostics.failure(t)}. No cluster load requested.", "start-failed")
            } finally { busy.set(false); handler.post { refreshPrompt() } }
        }
        pendingNotice()
    }
    private fun start(variant: ClusterThemeVariant, beforeTransaction: () -> Unit): String {
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
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { s.start(it, variant, beforeTransaction) }
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
    private fun releaseClaim(expected: Long) { if (lease.get() == expected && diagClaimed.compareAndSet(true, false)) releaseDiag() }
    private fun render(text: String, expectedLease: Long, pinned: ClusterThemeSession, requestedActivation: Long) {
        val j = JSONObject(text)
        if (j.getString("state") in setOf("IDLE", "CLEAN", "REFUSED")) releaseClaim(expectedLease)
        val query = j.optJSONObject("oemQuery")
        queryPending = query?.optString("state") == "PENDING"
        val queryText = if (query == null || query.length() == 0) "" else activity.getString(R.string.diag_cluster_oem_query_result,
            query.optString("state"), query.optString("detail"), query.opt("registration")?.toString() ?: "UNKNOWN",
            query.opt("native")?.toString() ?: "UNKNOWN", query.optString("selection")) + "\n\n"
        val summary = queryText + "${j.getString("state")}: ${j.getString("detail")}\nProfile ${j.optString("variant", "legacy marker")}; phase ${j.optString("phase", "unrecorded")}; daemon UID ${j.getInt("daemonUid")}; default queried=${j.getBoolean("defaultQueried")}; marker/theme/OEM observed by user=${j.getBoolean("markerObservedByUser")}/${j.optBoolean("themeObservedByUser")}/${j.getBoolean("oemObservedByUser")}"
        handler.post {
            if (!closed && pinned.isCurrent()) {
                if (active && activation == requestedActivation) {
                    displayed = j; displayedSession = pinned
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
    private fun rccKey(j: JSONObject): String = listOf("filename", "variant", "phase", "state", "mutated", "defaultQueried").joinToString(":") { (j.opt(it) ?: JSONObject.NULL).toString() }
    private fun refreshPrompt() {
        val j = displayed
        val s = displayedSession
        if (!active || closed || busy.get() && !statusPending || observationPending.get() || recoveryBusy.get() || j == null || s == null || !s.isCurrent() ||
            !j.optBoolean("mutated") || !ClusterThemePolicy.ownedName(j.optString("filename"))) {
            prompts.update("rcc", null); return
        }
        val restoring = j.optBoolean("defaultQueried") && !j.optBoolean("oemObservedByUser") && j.optString("state") in setOf("DEFAULT_QUERIED", "QUARANTINE")
        val variant = j.optString("variant")
        val loading = j.optString("state") == "AWAITING_OBSERVATION" && !j.optBoolean("defaultQueried") &&
            variant in ClusterThemeVariant.values().map { it.id } && !j.optBoolean(if (variant == "marker") "markerObservedByUser" else "themeObservedByUser")
        if (!restoring && !loading) { prompts.update("rcc", null); return }
        val snapshot = rccKey(j)
        val filename = j.optString("filename")
        prompts.update("rcc", ClusterObservationPrompts.Question("rcc:$snapshot", if (restoring) 90 else 50, s,
            if (restoring) R.string.diag_cluster_observe_oem_title else R.string.diag_cluster_observe_rcc_title,
            when { restoring -> R.string.diag_cluster_observe_oem_message; variant == "marker" -> R.string.diag_cluster_observe_marker_message; else -> R.string.diag_cluster_observe_theme_message },
            R.string.diag_cluster_observation_yes,
            current = { active && !closed && (!busy.get() || statusPending) && !observationPending.get() && !recoveryBusy.get() && displayedSession === s && displayed?.let(::rccKey) == snapshot },
            answer = {
                prompts.update("rcc", null)
                val expectedActivation = activation
                observe(if (restoring) "observe-oem" else "observe-rcc") {
                    check(session === s && s.isCurrent()) { "Visual confirmation belongs to a replaced session" }
                    val current = JSONObject(s.status())
                    check(active && !closed && activation == expectedActivation && rccKey(current) == snapshot) { "Visual confirmation expired; inspect the current cluster again" }
                    when { restoring -> s.observedOem(filename); variant == "marker" -> s.observedMarker(filename); else -> s.observedFullSurface(filename) }
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
