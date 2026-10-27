package com.byd.dashcast.proxy

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import com.byd.dashcast.clusterlive.ClusterThemeDiagnostics
import com.byd.dashcast.clusterlive.ClusterThemeVariant
import com.byd.dashcast.clusterlive.ClusterNativeProjectionDebt
import com.byd.dashcast.clusterlive.ClusterThemeProjectionDebt
import org.json.JSONObject
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract

/** Explicitly pinned existing ProxyDaemon. No connect(), generic shell, retry or daemon replacement. */
class ClusterThemeSession private constructor(private val binder: IBinder, private val app: Context?) {
    @Volatile private var filename = ""
    private val connectionToken = "appPid=${Process.myPid()};binderRef=${Integer.toHexString(System.identityHashCode(binder))}"
    companion object {
        fun pin(context: Context? = null): ClusterThemeSession {
            check(ProxyClient.supportsProtocol(31)) { "Connected ProxyDaemon protocol 31 required. Use existing connection controls; this test never starts/restarts it." }
            return ClusterThemeSession(ProxyClient.getProxyDaemonBinder() ?: error("Connect ProxyDaemon explicitly first"), context?.applicationContext)
        }
    }
    /** App-local Binder continuity only; this does not establish daemon-process identity. */
    fun isCurrent(): Boolean = ProxyClient.getProxyDaemonBinder() === binder && binder.isBinderAlive
    fun sharesConnection(other: ClusterThemeSession): Boolean = binder === other.binder
    fun status(): String = call(0)
    fun querySelection(): String {
        check(ProxyClient.supportsProtocol(33)) { "Connected ProxyDaemon protocol 33 required for read-only OEM query. No reconnect/replay." }
        return call(7)
    }
    fun start(source: ParcelFileDescriptor, beforeTransaction: () -> Unit): String = call(1, source, beforeTransaction)
    fun start(source: ParcelFileDescriptor, variant: ClusterThemeVariant, beforeTransaction: () -> Unit): String {
        check(!variant.isRecoveryOnly) { "Retired full-app profile is recovery-only" }
        require(!variant.isPersistent) { "Persistent profile requires the explicit Android IVI operation" }
        check(ProxyClient.supportsProtocol(variant.minimumProtocol)) {
            "Connected ProxyDaemon protocol ${variant.minimumProtocol} required for ${variant.id}. No reconnect/replay."
        }
        return call(variant.startOperation, source, beforeTransaction)
    }
    fun startIvi(source: ParcelFileDescriptor, variant: ClusterThemeVariant, validity: IBinder,
        beforeTransaction: () -> Unit): String {
        check(!variant.isRecoveryOnly) { "Retired full-app profile is recovery-only; no debt or IPC started" }
        check(ProxyClient.supportsProtocol(37)) { "Connected ProxyDaemon protocol 37 required for Android IVI RCC loading; no renderer fallback" }
        check(ProxyClient.supportsProtocol(variant.minimumProtocol)) { "Connected ProxyDaemon protocol ${variant.minimumProtocol} required for ${variant.id}; no fallback" }
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "")
        val operation = when (variant) {
            ClusterThemeVariant.MARKER -> 11
            ClusterThemeVariant.FULL_SURFACE_TEST -> 12
            ClusterThemeVariant.CORNER_MARKER -> 15
            ClusterThemeVariant.CORNER_MARKER_21F7735C -> 18
            ClusterThemeVariant.FULL_DASHBOARD_21F7735C -> 19
            ClusterThemeVariant.FULL_APP_21F7735C -> 24
            ClusterThemeVariant.FULLSCREEN_MINIMAL_21F7735C -> 30
        }
        val receipt = call(operation, source, {
            beforeTransaction()
            ClusterThemeProjectionDebt.begin(checkNotNull(app), nonce)
        }, payload = {
            it.writeInt(1); it.writeString(nonce); it.writeStrongBinder(validity)
        })
        if (JSONObject(receipt).optString("rccRejectedNonce") == nonce) app?.let { ClusterThemeProjectionDebt.clear(it, nonce) }
        return receipt
    }
    fun restoreIvi(validity: IBinder, retire: Boolean = false): String {
        check(ProxyClient.supportsProtocol(37)) { "Connected ProxyDaemon protocol 37 required for IVI recovery" }
        val nonce = app?.let { ClusterThemeProjectionDebt.nonce(it) }
            ?: filename.takeIf { com.byd.dashcast.clusterlive.ClusterThemePolicy.ownedName(it) }
                ?.removePrefix(com.byd.dashcast.clusterlive.ClusterThemePolicy.PREFIX)?.removeSuffix(".rcc")
            ?: error("No Android RCC recovery nonce recorded")
        return call(if (retire) 14 else 13, payload = { it.writeString(nonce); it.writeInt(1); it.writeInt(1); it.writeStrongBinder(validity) })
    }
    fun restore(): String { if (filename.isEmpty()) status(); return call(2) }
    fun prepareRestart(validity: IBinder): String {
        check(ProxyClient.supportsProtocol(45)) { "Connect the updated protocol-45 daemon first" }
        val nonce = recoveryNonce()
        return call(27, payload = { it.writeString(nonce); it.writeStrongBinder(validity) })
    }
    fun confirmRestart(round: String): String {
        check(ProxyClient.supportsProtocol(45) && round.matches(Regex("[0-9a-f]{32}")))
        return call(28, payload = { it.writeString(recoveryNonce()); it.writeString(round) })
    }
    fun cancelRestart(round: String): String {
        check(ProxyClient.supportsProtocol(45))
        return call(29, payload = { it.writeString(recoveryNonce()); it.writeString(round) })
    }
    private fun recoveryNonce(): String = filename.takeIf(com.byd.dashcast.clusterlive.ClusterThemePolicy::ownedName)
        ?.removePrefix(com.byd.dashcast.clusterlive.ClusterThemePolicy.PREFIX)?.removeSuffix(".rcc")
        ?: error("No positively owned RCC session")
    fun observedMarker(expectedFilename: String? = null): String = call(3, expectedFilename = expectedFilename)
    fun observedCorners(expectedFilename: String, round: String): String {
        check(ProxyClient.supportsProtocol(38)) { "Connected ProxyDaemon protocol 38 required for corner observation" }
        require(round.matches(Regex("[0-9a-f]{32}")))
        return call(16, expectedFilename = expectedFilename, payload = { it.writeString(round) })
    }
    fun verifyCorners(validity: IBinder): String {
        check(ProxyClient.supportsProtocol(41)) { "Connected ProxyDaemon protocol 41 required for source-independent read-only corner observation; no reconnect/replay" }
        val nonce = app?.let { ClusterThemeProjectionDebt.nonce(it) }
            ?: error("No Android RCC session recorded")
        return call(17, payload = { it.writeString(nonce); it.writeInt(1); it.writeInt(1); it.writeStrongBinder(validity) })
    }
    fun observedFullSurface(expectedFilename: String? = null): String {
        check(ProxyClient.supportsProtocol(32)) { "Connected ProxyDaemon protocol 32 required. No reconnect/replay." }
        return call(6, expectedFilename = expectedFilename)
    }
    fun dashboardProjection(open: Boolean, validity: IBinder, variant: ClusterThemeVariant): String {
        check(!open || !variant.isRecoveryOnly) { "Retired full-app opening is disabled; close remains available" }
        check(ProxyClient.supportsProtocol(43)) { "Connected protocol43 required; no reconnect/replay" }
        val nonce = app?.let { ClusterThemeProjectionDebt.nonce(it) } ?: error("No owned dashboard RCC session")
        return call(if (open) 20 else 21, payload = {
            it.writeString(nonce); it.writeInt(1); it.writeInt(1); it.writeStrongBinder(validity)
        })
    }
    fun observedDashboard(name: String, round: String): String {
        check(ProxyClient.supportsProtocol(43))
        require(round.matches(Regex("[0-9a-f]{32}")))
        return call(22, expectedFilename = name, payload = { it.writeString(round) })
    }
    fun applyFactorySelection(validity: IBinder): String {
        check(ProxyClient.supportsProtocol(44))
        val nonce = app?.let { ClusterThemeProjectionDebt.nonce(it) } ?: error("No owned factory recovery session")
        return call(26, payload = {
            it.writeString(nonce); it.writeInt(1); it.writeInt(1); it.writeStrongBinder(validity)
        })
    }
    fun currentDashboardDisplay(): String {
        check(ProxyClient.supportsProtocol(43)) { "Connected protocol43 required for current dashboard display evidence" }
        val nonce = app?.let { ClusterThemeProjectionDebt.nonce(it) } ?: error("No owned dashboard session")
        return call(23, payload = { it.writeString(nonce) })
    }
    internal fun fullAppAdas(round: String, action: Int, value: Int, count: Int, restoring: Boolean,
        pkg: String, display: Int, validity: IBinder): Boolean {
        check(restoring) { "Retired full-app masking is disabled; existing ADAS recovery remains available" }
        check(ProxyClient.supportsProtocol(44) && isCurrent())
        require(com.byd.dashcast.clusterlive.ClusterFullAppAdas.permits(action, value, count, restoring))
        val nonce = app?.let { ClusterThemeProjectionDebt.nonce(it) } ?: error("No owned full-app session")
        val response = call(25, payload = {
            it.writeString(nonce); it.writeString(round); it.writeInt(action); it.writeInt(value); it.writeInt(count)
            it.writeInt(if (restoring) 1 else 0); it.writeString(pkg); it.writeInt(display); it.writeStrongBinder(validity)
        })
        return JSONObject(response).optBoolean("fullAppAdasAccepted")
    }
    /** Exactly one transaction on this captured endpoint; never production retry/reconnect. */
    fun launchDashboardApplication(pkg: String, displayId: Int,
        variant: ClusterThemeVariant = ClusterThemeVariant.FULL_DASHBOARD_21F7735C): String? {
        check(!variant.isRecoveryOnly) { "Retired full-app application launch is unavailable; Restore remains available" }
        check(isCurrent()) { "Pinned dashboard daemon changed; no launch/replay" }
        check(!variant.isFullApp || ProxyClient.supportsProtocol(variant.minimumProtocol))
        val viewport = com.byd.dashcast.clusterlive.ClusterDashboardProfile.viewport(variant)
        val d = Parcel.obtain(); val r = Parcel.obtain()
        try {
            d.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            d.writeString(pkg); d.writeInt(0); d.writeInt(displayId)
            d.writeInt(viewport.width)
            d.writeInt(viewport.height)
            d.writeStrongBinder(null)
            check(binder.transact(ProxyDaemonContract.TXN_LAUNCH_AND_FORCE, d, r, 0)) { "Dashboard launch API unavailable" }
            r.readException(); val result = r.readString()
            check(r.dataAvail() == 0 && isCurrent()) { "Pinned dashboard daemon lost; launch result UNKNOWN, no replay" }
            return result
        } finally { r.recycle(); d.recycle() }
    }
    fun observedOem(expectedFilename: String? = null): String = call(4, expectedFilename = expectedFilename)
    fun nativeFull(validity: IBinder): String = nativeRequest(true, java.util.UUID.randomUUID().toString().replace("-", ""), validity)
    fun nativeNormal(nonce: String, validity: IBinder): String = nativeRequest(false, nonce, validity)
    fun nativeNormalObserved(nonce: String): String {
        requireNativeProtocol()
        require(nonce.matches(Regex("[0-9a-f]{32}")))
        val receipt = call(10, payload = { it.writeString(nonce) })
        val mode = JSONObject(receipt).getJSONObject("nativeMode")
        check(mode.getString("nonce") == nonce && mode.getString("state") == "CLOSED" && !mode.getBoolean("debt") && !mode.getBoolean("pending"))
        app?.let { ClusterNativeProjectionDebt.clear(it, nonce) }
        return receipt
    }
    private fun requireNativeProtocol() = check(ProxyClient.supportsProtocol(34)) {
        "Connected ProxyDaemon protocol 34 required for native Full-mode diagnostic. No reconnect/replay."
    }
    private fun nativeRequest(full: Boolean, nonce: String, validity: IBinder): String {
        requireNativeProtocol()
        require(nonce.matches(Regex("[0-9a-f]{32}")))
        val receipt = call(if (full) 8 else 9, beforeTransaction = {
            if (full) ClusterNativeProjectionDebt.begin(checkNotNull(app) { "App context required for durable native-mode debt" }, nonce)
        }, payload = {
            it.writeInt(if (full) 16 else 18); it.writeString(nonce)
            it.writeInt(1); it.writeInt(1); it.writeStrongBinder(validity)
        })
        if (full && JSONObject(receipt).optString("nativeRejectedNonce") == nonce) {
            app?.let { ClusterNativeProjectionDebt.clear(it, nonce) }
            ClusterThemeDiagnostics.local(app, "native-full-request", "REFUSED", JSONObject(receipt).optString("nativeRefusal").take(768))
        }
        return receipt
    }
    private fun call(operation: Int, source: ParcelFileDescriptor? = null, beforeTransaction: () -> Unit = {},
        expectedFilename: String? = null,
        payload: (Parcel) -> Unit = {}): String {
        val name = listOf("status", "start", "restore", "observe-marker", "observe-oem", "start-full-surface", "observe-full-surface", "oem-selection-query",
            "native-full-request", "native-normal-request", "native-normal-user-observation",
            "android-ivi-marker", "android-ivi-full-surface", "android-ivi-restore", "android-ivi-retire-carrier",
            "android-ivi-corner-marker", "observe-corner-marker", "verify-corner-marker-read-only", "android-ivi-corner-marker-21f7735c",
            "android-ivi-full-dashboard-21f7735c", "dashboard-open", "dashboard-close", "observe-dashboard", "dashboard-display-read",
            "android-ivi-full-app-21f7735c", "full-app-adas-step", "factory-apply-manual", "restart-prepare", "restart-confirm", "restart-cancel")[operation]
        var attempted = false
        val d = Parcel.obtain(); val r = Parcel.obtain()
        try {
            check(ProxyClient.getProxyDaemonBinder() === binder && binder.isBinderAlive) { "Pinned daemon changed/died. No replay; recovery state is UNKNOWN." }
            d.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR); d.writeInt(operation)
            if (operation in 2..4 || operation == 6 || operation == 16 || operation == 22) d.writeString(expectedFilename ?: filename)
            if (source != null) { source.writeToParcel(d, 0); d.writeInt(1) }
            payload(d)
            beforeTransaction()
            attempted = true
            check(binder.transact(ProxyDaemonContract.TXN_CLUSTER_LIVE_TEST, d, r, 0)) { "Live-test API unavailable" }
            r.readException()
            check(r.dataAvail() in 4..65536) { "Unsupported live-test reply" }
            val text = r.readString() ?: error("Missing live-test status")
            check(text.length <= 32768 && r.dataAvail() == 0) { "Oversized/malformed live-test status" }
            ClusterThemeDiagnostics.received(app, name, connectionToken, text)
            check(ProxyClient.getProxyDaemonBinder() === binder && binder.isBinderAlive) { "Daemon lost after call; remote completion UNKNOWN. No replay." }
            if (operation in 0..1 || operation == 5 || operation in 11..15 || operation == 18 || operation == 19 || operation == 24) filename = JSONObject(text).getString("filename")
            val state = JSONObject(text)
            val safe = state.optString("rccSafeNonce")
            if (safe.matches(Regex("[0-9a-f]{32}")) && app != null && ClusterThemeProjectionDebt.nonce(app) == safe &&
                state.optString("transport") == "android-ivi" && state.optString("state") in setOf("CLEAN", "REFUSED") &&
                state.has("rccDebt") && !state.getBoolean("rccDebt") &&
                state.optString("filename") == com.byd.dashcast.clusterlive.ClusterThemePolicy.PREFIX + safe + ".rcc")
                ClusterThemeProjectionDebt.clear(app, safe)
            return text
        } catch (error: Throwable) {
            ClusterThemeDiagnostics.local(app, name, "UNKNOWN", "Call failed; transactionAttempted=$attempted. No replay; physical completion UNKNOWN.",
                ClusterThemeDiagnostics.failure(error), connectionToken)
            throw error
        } finally { r.recycle(); d.recycle() }
    }
}
