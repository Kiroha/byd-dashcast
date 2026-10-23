package com.byd.dashcast.proxy

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import com.byd.dashcast.clusterlive.ClusterThemeDiagnostics
import com.byd.dashcast.clusterlive.ClusterThemeVariant
import com.byd.dashcast.clusterlive.ClusterNativeProjectionDebt
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
    fun status(): String = call(0)
    fun querySelection(): String {
        check(ProxyClient.supportsProtocol(33)) { "Connected ProxyDaemon protocol 33 required for read-only OEM query. No reconnect/replay." }
        return call(7)
    }
    fun start(source: ParcelFileDescriptor, beforeTransaction: () -> Unit): String = call(1, source, beforeTransaction)
    fun start(source: ParcelFileDescriptor, variant: ClusterThemeVariant, beforeTransaction: () -> Unit): String {
        check(ProxyClient.supportsProtocol(variant.minimumProtocol)) {
            "Connected ProxyDaemon protocol ${variant.minimumProtocol} required for ${variant.id}. No reconnect/replay."
        }
        return call(variant.startOperation, source, beforeTransaction)
    }
    fun restore(): String { if (filename.isEmpty()) status(); return call(2) }
    fun observedMarker(): String = call(3)
    fun observedFullSurface(): String {
        check(ProxyClient.supportsProtocol(32)) { "Connected ProxyDaemon protocol 32 required. No reconnect/replay." }
        return call(6)
    }
    fun observedOem(): String = call(4)
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
        payload: (Parcel) -> Unit = {}): String {
        val name = listOf("status", "start", "restore", "observe-marker", "observe-oem", "start-full-surface", "observe-full-surface", "oem-selection-query",
            "native-full-request", "native-normal-request", "native-normal-user-observation")[operation]
        var attempted = false
        val d = Parcel.obtain(); val r = Parcel.obtain()
        try {
            check(ProxyClient.getProxyDaemonBinder() === binder && binder.isBinderAlive) { "Pinned daemon changed/died. No replay; recovery state is UNKNOWN." }
            d.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR); d.writeInt(operation)
            if (operation in 2..4 || operation == 6) d.writeString(filename)
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
            if (operation in 0..1 || operation == 5) filename = JSONObject(text).getString("filename")
            return text
        } catch (error: Throwable) {
            ClusterThemeDiagnostics.local(app, name, "UNKNOWN", "Call failed; transactionAttempted=$attempted. No replay; physical completion UNKNOWN.",
                ClusterThemeDiagnostics.failure(error), connectionToken)
            throw error
        } finally { r.recycle(); d.recycle() }
    }
}
