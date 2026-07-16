package com.byd.dashcast.proxy;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Surface;

import com.byd.dashcast.proxy.daemon.ProxyDaemonContract;

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
final class ProxyDisplayVerbs {

    private ProxyDisplayVerbs() {}

    static void setOverscan(int displayId, int left, int top, int right, int bottom)
            throws RemoteException, ProxyClient.ProxyException {
        IBinder b = ProxyClient.sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyClient.ProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            data.writeInt(displayId);
            data.writeInt(left);
            data.writeInt(top);
            data.writeInt(right);
            data.writeInt(bottom);
            b.transact(ProxyDaemonContract.TXN_SET_OVERSCAN, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static int createVirtualDisplay(String name, int width, int height,
                                    int densityDpi, int flags, Surface surface)
            throws RemoteException, ProxyClient.ProxyException {
        IBinder b = ProxyClient.sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyClient.ProxyException("not connected");
        if (surface == null || !surface.isValid()) throw new ProxyClient.ProxyException("surface invalid");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            data.writeString(name == null ? "DashCast_VD" : name);
            data.writeInt(width);
            data.writeInt(height);
            data.writeInt(densityDpi);
            data.writeInt(flags);
            data.writeInt(1); // non-null Surface marker (readParcelable convention)
            surface.writeToParcel(data, 0);
            data.writeStrongBinder(ProxyDisplayResourceOwner.token());
            b.transact(ProxyDaemonContract.TXN_CREATE_VIRTUAL_DISPLAY, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static void releaseVirtualDisplay(int displayId)
            throws RemoteException, ProxyClient.ProxyException {
        IBinder b = ProxyClient.sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyClient.ProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            data.writeInt(displayId);
            b.transact(ProxyDaemonContract.TXN_RELEASE_VIRTUAL_DISPLAY, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
