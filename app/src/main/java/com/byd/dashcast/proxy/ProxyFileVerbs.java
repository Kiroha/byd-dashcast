package com.byd.dashcast.proxy;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import com.byd.dashcast.proxy.daemon.ProxyDaemonContract;

/**
 * Verb group: read files from inside the daemon (uid 2000 = shell).
 *
 * <p>The daemon can read {@code /data/local/tmp} files that SELinux hides from the app uid
 * ({@code untrusted_app} → {@code shell_data_file} reads are denied). This lets the app pull an
 * arbitrarily large capture (e.g. a raw unfiltered logcat) in bounded chunks without overflowing
 * a single Binder parcel.
 *
 * <p>Package-private; public API via {@link ProxyClient}.
 */
final class ProxyFileVerbs {

    private ProxyFileVerbs() {}

    /**
     * Read up to {@code maxLen} bytes of {@code path} at {@code offset}. Returns an empty array at
     * EOF so a pull loop can terminate on {@code chunk.length == 0}.
     */
    static byte[] readFileChunk(String path, long offset, int maxLen)
            throws RemoteException, ProxyClient.ProxyException {
        IBinder b = ProxyClient.sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyClient.ProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            data.writeString(path == null ? "" : path);
            data.writeLong(offset);
            data.writeInt(maxLen);
            b.transact(ProxyDaemonContract.TXN_READ_FILE_CHUNK, data, reply, 0);
            reply.readException();
            byte[] chunk = reply.createByteArray();
            return chunk == null ? new byte[0] : chunk;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
