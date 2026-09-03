package com.byd.dashcast.proxy

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

import com.byd.dashcast.proxy.daemon.ProxyDaemonContract

/**
 * Verb group: fission-mode operations (launch, move/resize, stack cleanup, task operations).
 *
 * Extracted from {@link ProxyClient}. Package-private; public API via {@link ProxyClient}.
 *
 * Verbs:
 * <ul>
 *   <li>{@link #launchAndForce}     — start + force-redirect to fission VirtualDisplay
 *   <li>{@link #moveAndResize}      — relocate an existing task to a display rect
 *   <li>{@link #cleanFissionStacks} — destroy zombie split-screen stacks on a display
 * </ul>
 */
internal object ProxyFissionVerbs {

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun launchAndForce(
        pkg: String?, activityCls: String?,
        displayId: Int, width: Int, height: Int
    ): String? {
        val b: IBinder? = ProxyClient.dispatchBinder()
        if (b == null || !b.isBinderAlive()) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeString(pkg)
            if (activityCls != null) {
                data.writeInt(1)
                data.writeString(activityCls)
            } else {
                data.writeInt(0)
            }
            data.writeInt(displayId)
            data.writeInt(width)
            data.writeInt(height)
            b.transact(ProxyDaemonContract.TXN_LAUNCH_AND_FORCE, data, reply, 0)
            reply.readException()
            return reply.readString()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun moveAndResize(
        pkg: String?, displayId: Int,
        left: Int, top: Int, right: Int, bottom: Int
    ): String? {
        val b: IBinder? = ProxyClient.dispatchBinder()
        if (b == null || !b.isBinderAlive()) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeString(pkg)
            data.writeInt(displayId)
            data.writeInt(left)
            data.writeInt(top)
            data.writeInt(right)
            data.writeInt(bottom)
            b.transact(ProxyDaemonContract.TXN_MOVE_AND_RESIZE, data, reply, 0)
            reply.readException()
            return reply.readString()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun cleanFissionStacks(displayId: Int): String? {
        val b: IBinder? = ProxyClient.dispatchBinder()
        if (b == null || !b.isBinderAlive()) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeInt(displayId)
            b.transact(ProxyDaemonContract.TXN_CLEAN_FISSION_STACKS, data, reply, 0)
            reply.readException()
            return reply.readString()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun cancelFissionWatchdog(packageName: String?): Boolean {
        val binder: IBinder? = ProxyClient.dispatchBinder()
        if (binder == null || !binder.isBinderAlive()) {
            throw ProxyClient.ProxyException("not connected")
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeString(packageName)
            binder.transact(ProxyDaemonContract.TXN_CANCEL_FISSION_WATCHDOG, data, reply, 0)
            reply.readException()
            return reply.readInt() == 1
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
