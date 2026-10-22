package com.byd.dashcast.proxy

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract

/** Explicitly pinned existing ProxyDaemon. No connect(), generic shell, retry or daemon replacement. */
class ClusterThemeSession private constructor(private val binder: IBinder) {
    @Volatile private var filename = ""
    companion object {
        fun pin(): ClusterThemeSession {
            check(ProxyClient.supportsProtocol(31)) { "Connected ProxyDaemon protocol 31 required. Use existing connection controls; this test never starts/restarts it." }
            return ClusterThemeSession(ProxyClient.getProxyDaemonBinder() ?: error("Connect ProxyDaemon explicitly first"))
        }
    }
    fun status(): String = call(0)
    fun start(source: ParcelFileDescriptor, beforeTransaction: () -> Unit): String = call(1, source, beforeTransaction)
    fun restore(): String { if (filename.isEmpty()) status(); return call(2) }
    fun observedMarker(): String = call(3)
    fun observedOem(): String = call(4)
    private fun call(operation: Int, source: ParcelFileDescriptor? = null, beforeTransaction: () -> Unit = {}): String {
        check(ProxyClient.getProxyDaemonBinder() === binder && binder.isBinderAlive) { "Pinned daemon changed/died. No replay; recovery state is UNKNOWN." }
        val d = Parcel.obtain(); val r = Parcel.obtain()
        try {
            d.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR); d.writeInt(operation)
            if (operation in 2..4) d.writeString(filename)
            if (source != null) { source.writeToParcel(d, 0); d.writeInt(1) }
            beforeTransaction()
            check(binder.transact(ProxyDaemonContract.TXN_CLUSTER_LIVE_TEST, d, r, 0)) { "Live-test API unavailable" }
            r.readException()
            check(r.dataAvail() in 4..65536) { "Unsupported live-test reply" }
            val text = r.readString() ?: error("Missing live-test status")
            check(text.length <= 32768 && r.dataAvail() == 0) { "Oversized/malformed live-test status" }
            check(ProxyClient.getProxyDaemonBinder() === binder && binder.isBinderAlive) { "Daemon lost after call; remote completion UNKNOWN. No replay." }
            if (operation in 0..1) filename = JSONObject(text).getString("filename")
            return text
        } finally { r.recycle(); d.recycle() }
    }
}
