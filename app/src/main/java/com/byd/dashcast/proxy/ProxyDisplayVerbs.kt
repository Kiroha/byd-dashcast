package com.byd.dashcast.proxy

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.view.Surface

import com.byd.dashcast.proxy.daemon.ProxyDaemonContract

/**
 * Verb group: display management (overscan, VirtualDisplay lifecycle, mirror).
 *
 * Extracted from {@link ProxyClient}. Package-private; public API via {@link ProxyClient}.
 *
 * Verbs:
 * <ul>
 *   <li>{@link #setOverscan}            — wm overscan via daemon
 *   <li>{@link #createVirtualDisplay}   — XDJA fission VD creation
 *   <li>{@link #releaseVirtualDisplay}  — XDJA fission VD teardown
 * </ul>
 */
internal object ProxyDisplayVerbs {

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun setOverscan(displayId: Int, left: Int, top: Int, right: Int, bottom: Int) {
        val b: IBinder? = ProxyClient.dispatchBinder()
        if (b == null || !b.isBinderAlive()) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeInt(displayId)
            data.writeInt(left)
            data.writeInt(top)
            data.writeInt(right)
            data.writeInt(bottom)
            b.transact(ProxyDaemonContract.TXN_SET_OVERSCAN, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun createVirtualDisplay(binder: IBinder, name: String?, width: Int, height: Int,
                             densityDpi: Int, flags: Int, surface: Surface?): Int {
        if (!binder.isBinderAlive()) throw ProxyClient.ProxyException("not connected")
        if (surface == null || !surface.isValid()) throw ProxyClient.ProxyException("surface invalid")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeString(name ?: "DashCast_VD")
            data.writeInt(width)
            data.writeInt(height)
            data.writeInt(densityDpi)
            data.writeInt(flags)
            data.writeInt(1) // non-null Surface marker (readParcelable convention)
            surface.writeToParcel(data, 0)
            data.writeStrongBinder(ProxyDisplayResourceOwner.token())
            binder.transact(ProxyDaemonContract.TXN_CREATE_VIRTUAL_DISPLAY, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun releaseVirtualDisplay(displayId: Int) {
        val b: IBinder? = ProxyClient.dispatchBinder()
        if (b == null || !b.isBinderAlive()) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeInt(displayId)
            b.transact(ProxyDaemonContract.TXN_RELEASE_VIRTUAL_DISPLAY, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
