package com.byd.dashcast.proxy.daemon

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process

/** Read-only, request-specific validity. The callback must read cached state without blocking. */
internal object LaunchRecoveryToken {
    private const val DESCRIPTOR = "com.byd.dashcast.proxy.ILaunchRecoveryValidity"
    private const val CHECK = IBinder.FIRST_CALL_TRANSACTION

    fun create(current: () -> Boolean): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != CHECK || reply == null) return false
            data.enforceInterface(DESCRIPTOR)
            val uid = getCallingUid()
            if (uid != 2000 && uid != 0 && uid != Process.myUid()) {
                throw SecurityException("Recovery validity requires daemon identity")
            }
            reply.writeNoException()
            reply.writeInt(if (current()) 1 else 0)
            return true
        }
    }

    fun readOptional(data: Parcel): IBinder? =
        if (data.dataAvail() == 0) null else data.readStrongBinder()

    fun isCurrent(token: IBinder?): Boolean {
        if (token == null) return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            if (!token.isBinderAlive) return false
            data.writeInterfaceToken(DESCRIPTOR)
            if (!token.transact(CHECK, data, reply, 0)) return false
            reply.readException()
            reply.dataAvail() == 4 && reply.readInt() == 1
        } catch (_: Exception) {
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
