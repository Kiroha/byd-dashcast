package com.byd.dashcast.ui.diag

import android.app.Activity
import android.app.AlertDialog
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.byd.dashcast.R
import com.byd.dashcast.cluster.display.ProjectionCommandBus
import com.byd.dashcast.cluster.display.ProjectionCommandSequencer
import com.byd.dashcast.clusterlive.ClusterThemeDiagnostics
import com.byd.dashcast.clusterlive.ClusterNativeProjectionDebt
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.proxy.ClusterThemeSession
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Manual native mode test, distinct from RCC loading and its restoration controls. */
internal class ClusterNativeFullControls(private val activity: Activity, header: LinearLayout, body: LinearLayout,
    private val claimDiag: () -> Boolean, private val releaseDiag: () -> Unit,
    private val transcript: (String) -> Unit,
    private val prompts: ClusterObservationPrompts = ClusterObservationPrompts(activity)) {
    companion object {
        private val work = Executors.newSingleThreadExecutor { Thread(it, "native-full-ui").apply { isDaemon = true } }
        private val recovery = Executors.newSingleThreadExecutor { Thread(it, "native-return-ui").apply { isDaemon = true } }
        private val busy = AtomicBoolean(false)
        private val returning = AtomicBoolean(false)
        private val observing = AtomicBoolean(false)
        private val leaseLock = Any()
        private var reservation: ProjectionCommandSequencer.Session? = null
    }
    private val app = activity.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    private var confirmation: AlertDialog? = null
    private var displayedNative: JSONObject? = null
    private var displayedSession: ClusterThemeSession? = null
    @Volatile private var active = false
    @Volatile private var activation = 0L
    private lateinit var returnButton: Button
    private val status = TextView(activity).apply { textSize = 14f; text = activity.getString(R.string.diag_cluster_native_idle) }
    init {
        body.addView(TextView(activity).apply { text = activity.getString(R.string.diag_cluster_native_section); textSize = 19f })
        body.addView(button(R.string.diag_cluster_native_full) { confirm(true) })
        body.addView(TextView(activity).apply { text = activity.getString(R.string.diag_cluster_native_help); textSize = 14f })
        body.addView(status)
        body.addView(button(R.string.diag_cluster_observation_again) { prompts.reopen() })
        returnButton = button(R.string.diag_cluster_native_normal) { confirm(false) }.apply {
            visibility = if (ClusterNativeProjectionDebt.blocked(app)) View.VISIBLE else View.GONE
        }
        header.addView(returnButton)
    }
    private fun button(label: Int, action: () -> Unit) = Button(activity).apply {
        text = activity.getString(label); isAllCaps = false
        minHeight = (48 * activity.resources.displayMetrics.density).toInt()
        setOnClickListener { action() }
    }
    private fun confirm(full: Boolean) {
        if (full && busy.get() || !full && returning.get()) { show(activity.getString(R.string.diag_cluster_native_busy)); return }
        val checks = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val parked = CheckBox(activity).apply { text = activity.getString(R.string.diag_cluster_native_parked) }
        val firmware = CheckBox(activity).apply { text = activity.getString(R.string.diag_cluster_native_firmware) }
        checks.addView(parked); checks.addView(firmware)
        val dialog = AlertDialog.Builder(activity).setTitle(if (full) R.string.diag_cluster_native_full else R.string.diag_cluster_native_normal)
            .setMessage(R.string.diag_cluster_native_confirm).setView(checks)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.diag_cluster_native_send, null).create()
        confirmation = dialog
        dialog.setOnShowListener {
            val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE); send.isEnabled = false
            parked.setOnCheckedChangeListener { _, _ -> send.isEnabled = parked.isChecked && firmware.isChecked }
            firmware.setOnCheckedChangeListener { _, _ -> send.isEnabled = parked.isChecked && firmware.isChecked }
            send.setOnClickListener { if (parked.isChecked && firmware.isChecked) { dialog.dismiss(); confirmation = null; request(full) } }
        }
        dialog.show()
    }
    private fun request(full: Boolean) {
        if (observing.get() || full && returning.get()) { show(activity.getString(R.string.diag_cluster_native_busy)); return }
        if (Build.VERSION.SDK_INT != 29 || !Platform.get().isAutoDetectedDiLink3()) {
            failed("native-mode-preflight", IllegalStateException("Positive DiLink 3 and API 29 required; fallback/override is insufficient")); return
        }
        val slot = if (full) busy else returning
        if (!slot.compareAndSet(false, true)) { show(activity.getString(R.string.diag_cluster_native_busy)); return }
        val claimed = full && claimDiag()
        if (full && !claimed) { slot.set(false); show(activity.getString(R.string.diag_cluster_native_busy)); return }
        val lease = synchronized(leaseLock) {
            reservation?.takeIf { ProjectionCommandBus.isCurrent(it) }
                ?: ProjectionCommandBus.reserveDiagnostic()?.also { reservation = it }
        }
        if (lease == null) {
            slot.set(false); if (claimed) releaseDiag(); show(activity.getString(R.string.diag_cluster_native_busy)); return
        }
        val requestedActivation = activation
        val validity = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 1) return false
                data.enforceInterface("com.byd.dashcast.NativeModeReservation")
                reply?.writeNoException(); reply?.writeInt(if (!closed && activation == requestedActivation && ProjectionCommandBus.isCurrent(lease)) 1 else 0)
                return true
            }
        }
        val operation = if (full) "native-full-request" else "native-normal-request"
        returnButton.visibility = View.VISIBLE
        com.byd.dashcast.cluster.display.ClusterManager.notifyProjectionStopped()
        ClusterThemeDiagnostics.local(app, operation, "PREFLIGHT", "User asserts parked and firmware13.1.33.2602030.1; movement UNKNOWN; API=${Build.VERSION.SDK_INT}; build=${Build.DISPLAY}; fingerprint=${Build.FINGERPRINT}; positiveDL3=true; monotonicMs=${SystemClock.elapsedRealtime()}.")
        show(activity.getString(R.string.diag_cluster_native_idle))
        (if (full) work else recovery).execute {
            var settled = false
            var debt = true
            var attempted = false
            var session: ClusterThemeSession? = null
            try {
                val s = ClusterThemeSession.pin(app); session = s
                val response = if (full) { attempted = true; s.nativeFull(validity) } else {
                    val current = JSONObject(s.status()).getJSONObject("nativeMode")
                    debt = current.optBoolean("debt"); settled = !current.optBoolean("pending")
                    val saved = ClusterNativeProjectionDebt.nonce(app)
                    check(debt || saved?.matches(Regex("[0-9a-f]{32}")) == true) { "No native-mode recovery debt recorded" }
                    val nonce = if (debt) current.getString("nonce") else checkNotNull(saved)
                    attempted = true; s.nativeNormal(nonce, validity)
                }
                showNative(response, s, requestedActivation)
                var mode = JSONObject(response).getJSONObject("nativeMode")
                val until = SystemClock.elapsedRealtime() + 30000
                while (mode.optBoolean("pending") && SystemClock.elapsedRealtime() < until) {
                    Thread.sleep(250)
                    val text = s.status(); mode = JSONObject(text).getJSONObject("nativeMode")
                    showNative(text, s, requestedActivation)
                }
                settled = !mode.optBoolean("pending")
                debt = mode.optBoolean("debt")
                if (!settled) throw IllegalStateException("Native helper still pending; reservation retained. No replay; return remains available.")
            } catch (error: Throwable) {
                if (!attempted) { settled = true; debt = false }
                else runCatching { session?.status()?.let { text ->
                    val mode = JSONObject(text).getJSONObject("nativeMode")
                    settled = !mode.optBoolean("pending"); debt = mode.optBoolean("debt"); session?.let { showNative(text, it, requestedActivation) }
                } }
                failed(operation, error)
            }
            finally {
                slot.set(false); if (claimed) releaseDiag()
                handler.post { refreshPrompts() }
                synchronized(leaseLock) {
                    if (settled && !debt && !busy.get() && !returning.get() && reservation === lease) {
                        ProjectionCommandBus.endSession(lease); reservation = null
                    }
                }
            }
        }
    }
    private fun releaseReservation() = synchronized(leaseLock) {
        if (!busy.get() && !returning.get()) reservation?.let {
            ProjectionCommandBus.endSession(it); reservation = null
        }
    }
    private var last = ""
    fun receivedStatus(text: String, session: ClusterThemeSession) = showNative(text, session)
    private fun showNative(text: String, session: ClusterThemeSession, requestedActivation: Long = activation) {
        val mode = JSONObject(text).optJSONObject("nativeMode") ?: return
        val summary = JSONObject(mode.toString()).apply { remove("quietRemainingMs"); remove("trace") }.toString()
        show("Native BYD mode receipt (historical; pixels/QML ACK/debounce UNKNOWN): $summary" +
            JSONObject(text).optString("nativeRefusal").takeIf { it.isNotEmpty() }?.let { "\nREFUSED: $it" }.orEmpty(), mode, session, requestedActivation)
    }
    private fun failed(operation: String, error: Throwable) {
        val message = "${ClusterThemeDiagnostics.failure(error)}; physical mode UNKNOWN; no reconnect/replay."
        ClusterThemeDiagnostics.local(app, operation, "UNKNOWN", message)
        show(message)
    }
    private fun show(text: String, native: JSONObject? = null, session: ClusterThemeSession? = null, requestedActivation: Long = activation) {
        handler.post {
            if (!closed) {
                if (native != null && active && requestedActivation == activation && session?.isCurrent() == true) {
                    displayedNative = native
                    displayedSession = displayedSession?.takeIf { it.isCurrent() && it.sharesConnection(session) } ?: session
                    returnButton.visibility = if (native.optBoolean("debt") || native.optBoolean("pending") || ClusterNativeProjectionDebt.blocked(app)) View.VISIBLE else View.GONE
                }
                status.text = text; if (last != text) { last = text; transcript(text) }
                refreshPrompts()
            }
        }
    }
    fun setActive(value: Boolean) {
        active = value
        activation++
        displayedNative = null; displayedSession = null
        if (!value) { confirmation?.dismiss(); confirmation = null; displayedNative = null; displayedSession = null }
        refreshPrompts()
    }
    private fun nativeKey(mode: JSONObject): String = listOf("nonce", "command", "state", "pending", "normalSettled", "admittedElapsedMs").joinToString(":") { (mode.opt(it) ?: JSONObject.NULL).toString() }
    private fun refreshPrompts() {
        val mode = displayedNative
        val s = displayedSession
        val ready = active && !closed && !busy.get() && !returning.get() && !observing.get() && mode != null && s != null && s.isCurrent() && !mode.optBoolean("pending")
        val normal = ready && normalEligible(checkNotNull(mode))
        val full = ready && !normal && mode!!.optBoolean("debt") && mode.optInt("command") == 16 && mode.optLong("admittedElapsedMs") > 0
        if (!normal && !full) { prompts.update("native", null); return }
        val shown = checkNotNull(mode)
        val pinned = checkNotNull(s)
        val snapshot = nativeKey(shown)
        val nonce = shown.optString("nonce")
        prompts.update("native", ClusterObservationPrompts.Question("native:$snapshot", if (normal) 100 else 30, pinned,
            if (normal) R.string.diag_cluster_observe_normal_title else R.string.diag_cluster_observe_full_title,
            if (normal) R.string.diag_cluster_observe_normal_message else R.string.diag_cluster_observe_full_message,
            if (normal) R.string.diag_cluster_observe_normal_yes else R.string.diag_cluster_observe_full_yes,
            current = { active && !closed && !busy.get() && !returning.get() && !observing.get() && displayedSession === pinned && displayedNative?.let(::nativeKey) == snapshot },
            answer = {
                if (normal) observeNormal(pinned, nonce, snapshot)
                else {
                    val text = "User reports native projection layout with OEM bands; nonce=$nonce; physical pixels and QML ACK UNKNOWN."
                    ClusterThemeDiagnostics.local(app, "native-full-user-observation", "USER_OBSERVATION", text); show(text)
                }
            }))
    }
    private fun normalEligible(mode: JSONObject): Boolean = mode.optBoolean("debt") && mode.optBoolean("normalSettled") ||
        mode.optString("state") == "CLOSED" && ClusterNativeProjectionDebt.nonce(app) == mode.optString("nonce")
    fun statusUnavailable() { displayedNative = null; displayedSession = null; refreshPrompts() }
    private fun observeNormal(pinned: ClusterThemeSession, nonce: String, snapshot: String) {
        if (!observing.compareAndSet(false, true)) return
        prompts.update("native", null)
        val requestedActivation = activation
        work.execute {
            try {
                check(active && !closed && activation == requestedActivation) { "Visual confirmation screen changed" }
                val current = JSONObject(pinned.status()).getJSONObject("nativeMode")
                check(active && !closed && activation == requestedActivation && nativeKey(current) == snapshot && normalEligible(current) && !current.optBoolean("pending")) { "Visual confirmation expired; inspect the current cluster again" }
                val receipt = pinned.nativeNormalObserved(nonce)
                showNative(receipt, pinned, requestedActivation)
                if (!JSONObject(receipt).getJSONObject("nativeMode").optBoolean("debt")) releaseReservation()
            } catch (error: Throwable) { failed("native-normal-user-observation", error) }
            finally { observing.set(false); handler.post { refreshPrompts() } }
        }
    }
    fun close() { setActive(false); closed = true; confirmation?.dismiss(); confirmation = null; handler.removeCallbacksAndMessages(null) }
}
