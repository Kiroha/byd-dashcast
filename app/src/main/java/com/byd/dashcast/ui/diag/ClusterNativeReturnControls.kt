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
import android.widget.LinearLayout
import com.byd.dashcast.R
import com.byd.dashcast.cluster.display.ProjectionCommandBus
import com.byd.dashcast.cluster.display.ProjectionCommandSequencer
import com.byd.dashcast.clusterlive.ClusterNativeProjectionDebt
import com.byd.dashcast.clusterlive.ClusterThemeDiagnostics
import com.byd.dashcast.clusterlive.ClusterThemeProjectionDebt
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.proxy.ClusterThemeSession
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recovery-only return from a native BYD projection mode left by an earlier DashCast build.
 * The native test itself is retired: this class never starts it, it only asks for the return and
 * the user's visual confirmation of the normal BYD display.
 */
internal class ClusterNativeReturnControls(private val activity: Activity, header: LinearLayout,
    private val transcript: (String) -> Unit,
    private val prompts: ClusterObservationPrompts = ClusterObservationPrompts(activity)) {
    companion object {
        private val work = Executors.newSingleThreadExecutor { Thread(it, "native-observe-ui").apply { isDaemon = true } }
        private val recovery = Executors.newSingleThreadExecutor { Thread(it, "native-return-ui").apply { isDaemon = true } }
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
    private val returnButton = Button(activity)
    private var last = ""
    init {
        header.addView(returnButton.apply {
            text = activity.getString(R.string.diag_cluster_native_normal); contentDescription = text; isAllCaps = false
            minHeight = (48 * activity.resources.displayMetrics.density).toInt()
            visibility = if (ClusterNativeProjectionDebt.blocked(app)) View.VISIBLE else View.GONE
            setOnClickListener { confirm() }
        })
    }
    private fun explainRccBlock(): Boolean {
        if (!ClusterThemeProjectionDebt.blocked(app)) return false
        val message = activity.getString(R.string.fs_recover_other)
        ClusterThemeDiagnostics.local(app, "native-request-rcc-blocked", "REFUSED", "RCC recovery pending; no native 16/18 command sent. $message")
        show(message)
        confirmation?.dismiss()
        confirmation = AlertDialog.Builder(activity).setTitle(R.string.diag_cluster_native_rcc_blocked)
            .setMessage(message).setPositiveButton(android.R.string.ok, null).show()
        return true
    }
    private fun confirm() {
        if (explainRccBlock()) return
        if (returning.get()) { show(activity.getString(R.string.fs_busy)); return }
        confirmation?.dismiss()
        val dialog = GuidedDialog(activity).apply {
            title = activity.getString(R.string.diag_cluster_native_normal)
            preview = ClusterPreviewView.Mode.STOCK
            steps = listOf(activity.getString(R.string.fs_native_step))
            expected = activity.getString(R.string.fs_restore_expected)
            checks = listOf(activity.getString(R.string.fs_check_parked), activity.getString(R.string.fs_check_restore))
            positive = activity.getString(R.string.fs_restore_confirm)
        }.create { popup -> popup.dismiss(); confirmation = null; request() }
        confirmation = dialog
        dialog.show()
    }
    private fun request() {
        if (explainRccBlock()) return
        if (observing.get()) { show(activity.getString(R.string.fs_busy)); return }
        if (Build.VERSION.SDK_INT != 29 || !Platform.get().isAutoDetectedDiLink3()) {
            failed("native-mode-preflight", IllegalStateException("Positive DiLink 3 and API 29 required; fallback/override is insufficient")); return
        }
        if (!returning.compareAndSet(false, true)) { show(activity.getString(R.string.fs_busy)); return }
        val lease = synchronized(leaseLock) {
            reservation?.takeIf { ProjectionCommandBus.isCurrent(it) }
                ?: ProjectionCommandBus.reserveDiagnostic()?.also { reservation = it }
        }
        if (lease == null) { returning.set(false); show(activity.getString(R.string.fs_busy)); return }
        val requestedActivation = activation
        val validity = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 1) return false
                data.enforceInterface("com.byd.dashcast.NativeModeReservation")
                reply?.writeNoException(); reply?.writeInt(if (!closed && activation == requestedActivation && ProjectionCommandBus.isCurrent(lease)) 1 else 0)
                return true
            }
        }
        val operation = "native-normal-request"
        returnButton.visibility = View.VISIBLE
        com.byd.dashcast.cluster.display.ClusterManager.notifyProjectionStopped()
        ClusterThemeDiagnostics.local(app, operation, "PREFLIGHT", "User asserts parked and restoring this vehicle; movement UNKNOWN; API=${Build.VERSION.SDK_INT}; build=${Build.DISPLAY}; fingerprint=${Build.FINGERPRINT}; positiveDL3=true; monotonicMs=${SystemClock.elapsedRealtime()}.")
        recovery.execute {
            var settled = false
            var debt = true
            var attempted = false
            var session: ClusterThemeSession? = null
            try {
                val s = ClusterThemeSession.pin(app); session = s
                val current = JSONObject(s.status()).getJSONObject("nativeMode")
                debt = current.optBoolean("debt"); settled = !current.optBoolean("pending")
                val saved = ClusterNativeProjectionDebt.nonce(app)
                check(debt || saved?.matches(Regex("[0-9a-f]{32}")) == true) { "No native-mode recovery debt recorded" }
                val nonce = if (debt) current.getString("nonce") else checkNotNull(saved)
                attempted = true
                val response = s.nativeNormal(nonce, validity)
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
                returning.set(false)
                handler.post { refreshPrompts() }
                synchronized(leaseLock) {
                    if (settled && !debt && !returning.get() && reservation === lease) {
                        ProjectionCommandBus.endSession(lease); reservation = null
                    }
                }
            }
        }
    }
    private fun releaseReservation() = synchronized(leaseLock) {
        if (!returning.get()) reservation?.let { ProjectionCommandBus.endSession(it); reservation = null }
    }
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
                if (last != text) { last = text; transcript(text) }
                refreshPrompts()
            }
        }
    }
    fun setActive(value: Boolean) {
        active = value
        activation++
        displayedNative = null; displayedSession = null
        if (!value) { confirmation?.dismiss(); confirmation = null }
        refreshPrompts()
    }
    private fun nativeKey(mode: JSONObject): String = listOf("nonce", "command", "state", "pending", "normalSettled", "admittedElapsedMs").joinToString(":") { (mode.opt(it) ?: JSONObject.NULL).toString() }
    private fun refreshPrompts() {
        val mode = displayedNative
        val s = displayedSession
        if (!active || closed || returning.get() || observing.get() || mode == null || s == null || !s.isCurrent() ||
            mode.optBoolean("pending") || !normalEligible(mode)) {
            prompts.update("native", null); return
        }
        val snapshot = nativeKey(mode)
        val nonce = mode.optString("nonce")
        prompts.update("native", ClusterObservationPrompts.Question("native:$snapshot", 100, s,
            R.string.diag_cluster_observe_normal_title, R.string.diag_cluster_observe_normal_message, R.string.diag_cluster_observe_normal_yes,
            current = { active && !closed && !returning.get() && !observing.get() && displayedSession === s && displayedNative?.let(::nativeKey) == snapshot },
            answer = { observeNormal(s, nonce, snapshot) }, preview = ClusterPreviewView.Mode.STOCK))
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
