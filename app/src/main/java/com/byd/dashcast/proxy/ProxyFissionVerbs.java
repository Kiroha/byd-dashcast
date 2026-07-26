package com.byd.dashcast.proxy;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import com.byd.dashcast.proxy.daemon.ProxyDaemonContract;

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
final class ProxyFissionVerbs {

    private ProxyFissionVerbs() {}

    static String launchAndForce(String pkg, String activityCls,
                                  int displayId, int width, int height)
            throws RemoteException, ProxyClient.ProxyException {
        IBinder b = ProxyClient.sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyClient.ProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            data.writeString(pkg);
            if (activityCls != null) {
                data.writeInt(1);
                data.writeString(activityCls);
            } else {
                data.writeInt(0);
            }
            data.writeInt(displayId);
            data.writeInt(width);
            data.writeInt(height);
            b.transact(ProxyDaemonContract.TXN_LAUNCH_AND_FORCE, data, reply, 0);
            reply.readException();
            return reply.readString();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static String moveAndResize(String pkg, int displayId,
                                 int left, int top, int right, int bottom)
            throws RemoteException, ProxyClient.ProxyException {
        IBinder b = ProxyClient.sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyClient.ProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            data.writeString(pkg);
            data.writeInt(displayId);
            data.writeInt(left);
            data.writeInt(top);
            data.writeInt(right);
            data.writeInt(bottom);
            b.transact(ProxyDaemonContract.TXN_MOVE_AND_RESIZE, data, reply, 0);
            reply.readException();
            return reply.readString();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static String cleanFissionStacks(int displayId)
            throws RemoteException, ProxyClient.ProxyException {
        IBinder b = ProxyClient.sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyClient.ProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            data.writeInt(displayId);
            b.transact(ProxyDaemonContract.TXN_CLEAN_FISSION_STACKS, data, reply, 0);
            reply.readException();
            return reply.readString();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static boolean cancelFissionWatchdog(String packageName)
            throws RemoteException, ProxyClient.ProxyException {
        IBinder binder = ProxyClient.sBinder;
        if (binder == null || !binder.isBinderAlive()) {
            throw new ProxyClient.ProxyException("not connected");
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            data.writeString(packageName);
            binder.transact(ProxyDaemonContract.TXN_CANCEL_FISSION_WATCHDOG, data, reply, 0);
            reply.readException();
            return reply.readInt() == 1;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
