package com.byd.dashcast.proxy

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import com.byd.dashcast.system.CanBatchOperation

/**
 * Verb group: BYD CAN bus write operations via daemon uid=2000.
 *
 * Extracted from [ProxyClient]. Package-private; public API via [ProxyClient].
 *
 * Requires `BYDAutoInstrumentDevice` / `BYDAutoSettingDevice` accessible
 * from the shell uid context inside the daemon.
 *
 * Verbs:
 *  - [canNaviStatus]      — set navigation HUD status on cluster
 *  - [canInstrumentInt]   — write int to a BYD CAN instrument feature
 *  - [canInstrumentBytes] — write byte buffer to a BYD CAN instrument feature
 *  - [canSettingInt]      — write int to a BYD CAN setting feature
 */
internal object ProxyCanVerbs {

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun canNaviStatus(status: Int): Int {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeInt(status)
            b.transact(ProxyDaemonContract.TXN_CAN_NAVI_STATUS, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun canInstrumentInt(featureId: Int, value: Int): Int {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeInt(featureId)
            data.writeInt(value)
            b.transact(ProxyDaemonContract.TXN_CAN_INSTRUMENT_INT, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun canInstrumentBytes(featureId: Int, bytes: ByteArray?): Int {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeInt(featureId)
            data.writeByteArray(bytes ?: ByteArray(0))
            b.transact(ProxyDaemonContract.TXN_CAN_INSTRUMENT_BYTES, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun canSettingInt(featureId: Int, value: Int): Int {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeInt(featureId)
            data.writeInt(value)
            b.transact(ProxyDaemonContract.TXN_CAN_SETTING_INT, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun canBatch(binder: IBinder, operations: List<CanBatchOperation>?): Int {
        if (!binder.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        if (operations == null || operations.isEmpty() ||
            operations.size > CanBatchOperation.MAX_BATCH_SIZE
        ) {
            throw ProxyClient.ProxyException("invalid CAN batch size")
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeInt(operations.size)
            for (operation in operations) {
                data.writeInt(operation.type)
                data.writeInt(operation.featureId)
                data.writeInt(operation.intValue)
                data.writeByteArray(operation.getBytes())
            }
            binder.transact(ProxyDaemonContract.TXN_CAN_BATCH, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun canInstrumentGet(featureId: Int): Int {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeInt(featureId)
            b.transact(ProxyDaemonContract.TXN_CAN_INSTRUMENT_GET, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun canSettingDouble(featureId: Int, value: Double): Int {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeInt(featureId)
            data.writeDouble(value)
            b.transact(ProxyDaemonContract.TXN_CAN_SETTING_DOUBLE, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun canSettingGet(featureId: Int): Int {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeInt(featureId)
            b.transact(ProxyDaemonContract.TXN_CAN_SETTING_GET, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun canListenStart(): String? {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            b.transact(ProxyDaemonContract.TXN_CAN_LISTEN_START, data, reply, 0)
            reply.readException()
            return reply.readString()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun canListenDrain(): String? {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            b.transact(ProxyDaemonContract.TXN_CAN_LISTEN_DRAIN, data, reply, 0)
            reply.readException()
            return reply.readString()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun aaosHalProbe(): String? {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            b.transact(ProxyDaemonContract.TXN_AAOS_HAL_PROBE, data, reply, 0)
            reply.readException()
            return reply.readString()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun canListenClear() {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            b.transact(ProxyDaemonContract.TXN_CAN_LISTEN_CLEAR, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @Throws(RemoteException::class, ProxyClient.ProxyException::class)
    fun canListenMark(label: String?) {
        val b: IBinder? = ProxyClient.sBinder
        if (b == null || !b.isBinderAlive) throw ProxyClient.ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            data.writeString(label ?: "")
            b.transact(ProxyDaemonContract.TXN_CAN_LISTEN_MARK, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
