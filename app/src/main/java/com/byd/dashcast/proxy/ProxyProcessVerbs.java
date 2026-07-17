package com.byd.dashcast.proxy;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import com.byd.dashcast.proxy.daemon.ProxyDaemonContract;
import com.byd.dashcast.infrastructure.task.TaskLocation;

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
final class ProxyProcessVerbs {

    private ProxyProcessVerbs() {}

    static String runShell(String cmd) throws RemoteException, ProxyClient.ProxyException {
        IBinder b = ProxyClient.sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyClient.ProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            data.writeString(cmd);
            b.transact(ProxyDaemonContract.TXN_EXEC, data, reply, 0);
            reply.readException();
            int exit = reply.readInt();
            String output = reply.readString();
            if (exit != 0 && output != null && output.startsWith("ERR ")) {
                throw new ProxyClient.ProxyException(output.substring(4));
            }
            return output == null ? "" : output;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static String getPidsByPackage(String packageName)
            throws RemoteException, ProxyClient.ProxyException {
        IBinder b = ProxyClient.sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyClient.ProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            data.writeString(packageName);
            b.transact(ProxyDaemonContract.TXN_GET_PIDS, data, reply, 0);
            reply.readException();
            return reply.readString();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static void autoContainerSendInfo(int type, int info, String str)
            throws RemoteException, ProxyClient.ProxyException {
        IBinder b = ProxyClient.sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyClient.ProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            data.writeInt(type);
            data.writeInt(info);
            data.writeString(str);
            b.transact(ProxyDaemonContract.TXN_AUTOCONTAINER_SEND_INFO, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static int autoContainerSendInfoResult(int type, int info, String str)
            throws RemoteException, ProxyClient.ProxyException {
        IBinder b = ProxyClient.sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyClient.ProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            data.writeInt(type);
            data.writeInt(info);
            data.writeString(str);
            b.transact(ProxyDaemonContract.TXN_AUTOCONTAINER_SEND_INFO_RESULT, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static void forceStopPackage(String packageName, int userId)
            throws RemoteException, ProxyClient.ProxyException {
        IBinder b = ProxyClient.sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyClient.ProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            data.writeString(packageName);
            data.writeInt(userId);
            b.transact(ProxyDaemonContract.TXN_FORCE_STOP_PACKAGE, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static int findTaskIdForPackage(String packageName)
            throws RemoteException, ProxyClient.ProxyException {
        IBinder b = ProxyClient.sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyClient.ProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            data.writeString(packageName == null ? "" : packageName);
            b.transact(ProxyDaemonContract.TXN_FIND_TASK_FOR_PACKAGE, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static TaskLocation findTaskLocationForPackage(String packageName)
            throws RemoteException, ProxyClient.ProxyException {
        IBinder b = ProxyClient.sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyClient.ProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            data.writeString(packageName == null ? "" : packageName);
            b.transact(ProxyDaemonContract.TXN_FIND_TASK_LOCATION, data, reply, 0);
            reply.readException();
            return TaskLocation.fromWire(reply.readInt(), reply.readInt(), reply.readInt());
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static void removeTask(int taskId)
            throws RemoteException, ProxyClient.ProxyException {
        IBinder b = ProxyClient.sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyClient.ProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            data.writeInt(taskId);
            b.transact(ProxyDaemonContract.TXN_REMOVE_TASK, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
