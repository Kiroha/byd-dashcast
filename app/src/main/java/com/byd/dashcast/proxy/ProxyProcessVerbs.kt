package com.byd.dashcast.proxy

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import com.byd.dashcast.infrastructure.task.TaskLocation

/**
 * Verb group: process management and task discovery.
 *
 * Extracted from {@link ProxyClient} to keep that class under 400 lines.
 * All methods are package-private; callers use the {@link ProxyClient} facade.
 *
 * Verbs:
 * <ul>
 *   <li>{@link #runShell}            — execute arbitrary shell command via daemon
 *   <li>{@link #getPidsByPackage}    — list PIDs matching a package name
 *   <li>{@link #autoContainerSendInfo} — BYD sendInfo CAN trigger
 *   <li>{@link #forceStopPackage}    — am force-stop via daemon
 *   <li>{@link #findTaskIdForPackage} — ATM task lookup via daemon reflection
 *   <li>{@link #removeTask}          — ATM task removal via daemon reflection
 * </ul>
 */
internal object ProxyProcessVerbs {

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun runShell(cmd: String?): String {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeString(cmd)
            b.transact(ProxyDaemonContract.TXN_EXEC, data, reply, 0)
            reply.readException()
            val exit = reply.readInt()
            val output = reply.readString()
            if (exit != 0 && output != null && output.startsWith("ERR ")) {
                throw ProxyClient.ProxyException(output.substring(4))
            }
            return output ?: ""
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun getPidsByPackage(packageName: String?): String? {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeString(packageName)
            b.transact(ProxyDaemonContract.TXN_GET_PIDS, data, reply, 0)
            reply.readException()
            return reply.readString()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun autoContainerSendInfo(type: Int, info: Int, str: String?) {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeInt(type)
            data.writeInt(info)
            data.writeString(str)
            b.transact(ProxyDaemonContract.TXN_AUTOCONTAINER_SEND_INFO, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun autoContainerSendInfoResult(type: Int, info: Int, str: String?): Int {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeInt(type)
            data.writeInt(info)
            data.writeString(str)
            b.transact(ProxyDaemonContract.TXN_AUTOCONTAINER_SEND_INFO_RESULT, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun forceStopPackage(packageName: String?, userId: Int) {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeString(packageName)
            data.writeInt(userId)
            b.transact(ProxyDaemonContract.TXN_FORCE_STOP_PACKAGE, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun findTaskIdForPackage(packageName: String?): Int {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeString(packageName ?: "")
            b.transact(ProxyDaemonContract.TXN_FIND_TASK_FOR_PACKAGE, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun findTaskLocationForPackage(packageName: String?): TaskLocation {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeString(packageName ?: "")
            b.transact(ProxyDaemonContract.TXN_FIND_TASK_LOCATION, data, reply, 0)
            reply.readException()
            return TaskLocation.fromWire(reply.readInt(), reply.readInt(), reply.readInt())
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun removeTask(taskId: Int) {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeInt(taskId)
            b.transact(ProxyDaemonContract.TXN_REMOVE_TASK, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
