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
import com.byd.dashcast.clusterlive.ClusterThemePolicy
import com.byd.dashcast.clusterlive.ClusterThemeDiagnostics
import com.byd.dashcast.clusterlive.ClusterThemeVariant
import com.byd.dashcast.proxy.ClusterThemeSession
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Reserved Restore lane, independent of both the command lane and Diag's global busy gate. */
internal class ClusterThemeLiveControls(private val activity: Activity, header: LinearLayout, controls: LinearLayout,
    private val claimDiag: () -> Boolean, private val releaseDiag: () -> Unit,
    private val otherBusy: () -> Boolean, private val transcript: (String) -> Unit) {
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
    private val status = TextView(activity).apply {
        textSize = 14f; maxLines = 4
        text = "Physical cluster state UNKNOWN. Read the current connected daemon status before drawing conclusions."
    }
    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            val s = session
            if (s != null) submit("status", automatic = true) { s.status() }
            handler.postDelayed(this, 1500)
        }
    }
    init {
        // Measure recovery FIRST: later fixed export headers can exhaust a short viewport.
        val headerIndex = 0
        header.addView(status, headerIndex)
        header.addView(Button(activity).apply {
            text = "Restore original cluster selection"
            contentDescription = "Restore original cluster selection, always available"
            setOnClickListener {
                confirmation?.dismiss(); confirmation = null
                abortBeforeSend.set(true)
                if (recoveryBusy.compareAndSet(false, true)) {
                    val requestedLease = lease.get()
                    recovery.execute {
                        try {
                            val s = session ?: ClusterThemeSession.pin(app).also { session = it }
                            render(s.restore(), requestedLease)
                        } catch (t: Throwable) { show("Restore UNKNOWN/refused: ${ClusterThemeDiagnostics.failure(t)}. No reconnect/replay. Read current connected daemon status for recovery.", "restore") }
                        finally { recoveryBusy.set(false) }
                    }
                    handler.postDelayed({ if (recoveryBusy.get()) show("Restore pending: remote completion UNKNOWN. No second request is replayed.", "restore-pending") }, 3000)
                } else show("Restore already pending; no second request replayed.", "restore-busy")
            }
        }, headerIndex)
        controls.addView(Button(activity).apply {
            text = "Change physical cluster — passive TEST marker (experimental)"
            setOnClickListener {
                if (!busy.get() && !recoveryBusy.get() && !otherBusy() && !diagClaimed.get()) confirm(ClusterThemeVariant.MARKER)
                else show("Another request/diagnostic is active. Restore remains available.", "start-busy")
            }
        })
        controls.addView(Button(activity).apply {
            text = "Test full cluster theme — night + Full (experimental)"
            setOnClickListener {
                if (!busy.get() && !recoveryBusy.get() && !otherBusy() && !diagClaimed.get()) confirm(ClusterThemeVariant.FULL_SURFACE_TEST)
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
            text = "I can see DASHCAST TEST on the physical cluster"
            setOnClickListener { submit("observe-marker") { checkNotNull(session) { "Read existing session status first" }.observedMarker() } }
        })
        controls.addView(Button(activity).apply {
            text = "I can see DASHCAST TEST, four cyan canvas corners and readable OEM instruments"
            setOnClickListener { submit("observe-full-surface") { checkNotNull(session) { "Read existing session status first" }.observedFullSurface() } }
        })
        controls.addView(Button(activity).apply {
            text = "I can see the original OEM cluster again"
            setOnClickListener { submit("observe-oem") { checkNotNull(session) { "Read existing session status first" }.observedOem() } }
        })
        handler.post(poll)
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
        diagClaimed.set(true); val myLease = lease.incrementAndGet(); abortBeforeSend.set(false)
        show("${variant.id} preflight pending. Parked is your assertion, not a detected vehicle state.", "start-preflight")
        work.execute {
            var ipcBegan = false
            try { render(start(variant) { check(!abortBeforeSend.get()) { "Cancelled before start transmission" }; ipcBegan = true }, myLease) }
            catch (t: Throwable) {
                if (!ipcBegan) releaseClaim(myLease)
                show(if (ipcBegan) "UNKNOWN after start transmission: ${ClusterThemeDiagnostics.failure(t)}. No replay; use Restore."
                    else "REFUSED before start transmission: ${ClusterThemeDiagnostics.failure(t)}. No cluster load requested.", "start-failed")
            } finally { busy.set(false) }
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
        work.execute {
            try { render(action(), myLease) } catch (t: Throwable) { show("REFUSED/UNKNOWN: ${ClusterThemeDiagnostics.failure(t)}. No request replayed.", "$operation-failed") }
            finally { busy.set(false) }
        }
        pendingNotice()
    }
    private fun pendingNotice() { handler.postDelayed({ if (busy.get()) show("Request pending: remote completion UNKNOWN. Restore remains available.", "request-pending") }, 3000) }
    private fun releaseClaim(expected: Long) { if (lease.get() == expected && diagClaimed.compareAndSet(true, false)) releaseDiag() }
    private fun render(text: String, expectedLease: Long) {
        val j = JSONObject(text)
        if (j.getString("state") in setOf("IDLE", "CLEAN", "REFUSED")) releaseClaim(expectedLease)
        val summary = "${j.getString("state")}: ${j.getString("detail")}\nProfile ${j.optString("variant", "legacy marker")}; phase ${j.optString("phase", "unrecorded")}; daemon UID ${j.getInt("daemonUid")}; default queried=${j.getBoolean("defaultQueried")}; marker/theme/OEM observed by user=${j.getBoolean("markerObservedByUser")}/${j.optBoolean("themeObservedByUser")}/${j.getBoolean("oemObservedByUser")}"
        handler.post {
            if (!closed) {
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
    fun close() { closed = true; confirmation?.dismiss(); confirmation = null; handler.removeCallbacksAndMessages(null) }
}
