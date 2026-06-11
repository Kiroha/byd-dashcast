package com.byd.dashcast.proxy;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import com.byd.dashcast.proxy.daemon.ProxyDaemonContract;

/**
 * Verb group: BYD CAN bus write operations via daemon uid=2000.
 *
 * Extracted from {@link ProxyClient}. Package-private; public API via {@link ProxyClient}.
 *
 * Requires {@code BYDAutoInstrumentDevice} / {@code BYDAutoSettingDevice} accessible
 * from the shell uid context inside the daemon.
 *
 * Verbs:
 * <ul>
 *   <li>{@link #canNaviStatus}      — set navigation HUD status on cluster
 *   <li>{@link #canInstrumentInt}   — write int to a BYD CAN instrument feature
 *   <li>{@link #canInstrumentBytes} — write byte buffer to a BYD CAN instrument feature
 *   <li>{@link #canSettingInt}      — write int to a BYD CAN setting feature
 * </ul>
 */
final class ProxyCanVerbs {

    private ProxyCanVerbs() {}

    static int canNaviStatus(int status) throws RemoteException, ProxyClient.ProxyException {
        IBinder b = ProxyClient.sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyClient.ProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            data.writeInt(status);
            b.transact(ProxyDaemonContract.TXN_CAN_NAVI_STATUS, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static int canInstrumentInt(int featureId, int value)
            throws RemoteException, ProxyClient.ProxyException {
        IBinder b = ProxyClient.sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyClient.ProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            data.writeInt(featureId);
            data.writeInt(value);
            b.transact(ProxyDaemonContract.TXN_CAN_INSTRUMENT_INT, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static int canInstrumentBytes(int featureId, byte[] bytes)
            throws RemoteException, ProxyClient.ProxyException {
        IBinder b = ProxyClient.sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyClient.ProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            data.writeInt(featureId);
            data.writeByteArray(bytes == null ? new byte[0] : bytes);
            b.transact(ProxyDaemonContract.TXN_CAN_INSTRUMENT_BYTES, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static int canSettingInt(int featureId, int value)
            throws RemoteException, ProxyClient.ProxyException {
        IBinder b = ProxyClient.sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyClient.ProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            data.writeInt(featureId);
            data.writeInt(value);
            b.transact(ProxyDaemonContract.TXN_CAN_SETTING_INT, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
