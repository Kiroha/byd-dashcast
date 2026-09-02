package com.byd.dashcast.proxy

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

import com.byd.dashcast.proxy.daemon.ProxyDaemonContract

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
internal object ProxyFileVerbs {

    /**
     * Read up to {@code maxLen} bytes of {@code path} at {@code offset}. Returns an empty array at
     * EOF so a pull loop can terminate on {@code chunk.length == 0}.
     */
    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun readFileChunk(path: String?, offset: Long, maxLen: Int): ByteArray {
        val b: IBinder? = ProxyClient.dispatchBinder()
        if (b == null || !b.isBinderAlive()) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeString(path ?: "")
            data.writeLong(offset)
            data.writeInt(maxLen)
            b.transact(ProxyDaemonContract.TXN_READ_FILE_CHUNK, data, reply, 0)
            reply.readException()
            val chunk = reply.createByteArray()
            return chunk ?: ByteArray(0)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
