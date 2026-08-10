package com.byd.dashcast.proxy

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

import com.byd.dashcast.proxy.daemon.ProxyDaemonContract

/**
 * Verb group: read-only / diagnostic probes of native services discovered by firmware RE, never
 * called before v1.8.26-beta. Extracted from [ProxyClient]; package-private, public API via
 * [ProxyClient]. See [ProxyDaemonContract]'s v23 history entry for provenance.
 *
 *  - [fissionGetAutoCarDisplay] — one-shot read of the FissionHostSvc display registry (DL3 only).
 *  - [autoContainerRegisterCallback] — arms the daemon's AutoContainer callback listener.
 *  - [projectionTraceStart] / [projectionTraceDrain] — samples the registry across a normal
 *    projection start/stop cycle.
 */
internal object ProxyNativeServiceVerbs {

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun fissionGetAutoCarDisplay(): String? {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            b.transact(ProxyDaemonContract.TXN_FISSION_GET_AUTOCAR_DISPLAY, data, reply, 0)
            reply.readException()
            return reply.readString()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun autoContainerRegisterCallback(): Int {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            b.transact(ProxyDaemonContract.TXN_AUTOCONTAINER_REGISTER_CALLBACK, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun projectionTraceStart() {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            b.transact(ProxyDaemonContract.TXN_PROJECTION_TRACE_START, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun projectionTraceDrain(): String? {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            b.transact(ProxyDaemonContract.TXN_PROJECTION_TRACE_DRAIN, data, reply, 0)
            reply.readException()
            return reply.readString()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
