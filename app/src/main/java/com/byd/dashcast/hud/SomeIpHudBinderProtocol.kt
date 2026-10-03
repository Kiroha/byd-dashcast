package com.byd.dashcast.hud

import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import androidx.annotation.WorkerThread

/** A server reply is a transport observation, not proof that a HUD rendered the frame. */
internal sealed class SomeIpHudTransactionResult {
    data class Reply(val code: Int) : SomeIpHudTransactionResult()
    data class Failure(val reason: Reason) : SomeIpHudTransactionResult()

    enum class Reason {
        NOT_CONNECTED,
        SESSION_CLOSED,
        INVALID_INTERFACE,
        TRANSACTION_REJECTED,
        BINDER_DIED,
        REMOTE_EXCEPTION,
        PERMISSION_DENIED,
        INVALID_REPLY
    }
}

/**
 * Client wire contract observed in OpenBYD 2.5, independently of the selected HUD profile.
 *
 * Service/topic IDs remain Longs. TX6 contains BOTH the explicit payload size and the byte-array
 * length written by Parcel. The meaning of the fixed 1 and 0L is not established by the client.
 * No SDK status or server return code is translated into a claim of visual delivery.
 */
internal class SomeIpHudBinderProtocol(
    private val binder: IBinder,
    private val obtainParcel: () -> Parcel = { Parcel.obtain() }
) {
    @WorkerThread
    fun startService(serviceId: Long): SomeIpHudTransactionResult = transact(4) {
        writeLong(serviceId)
    }

    @WorkerThread
    fun stopService(serviceId: Long): SomeIpHudTransactionResult = transact(5) {
        writeLong(serviceId)
    }

    @WorkerThread
    fun fireEvent(topicId: Long, payload: ByteArray): SomeIpHudTransactionResult = transact(6) {
        writeInt(1)
        writeLong(topicId)
        writeLong(0L)
        writeInt(payload.size)
        writeByteArray(payload)
    }

    private fun transact(code: Int, write: Parcel.() -> Unit): SomeIpHudTransactionResult {
        val request = obtainParcel()
        try {
            val reply = obtainParcel()
            try {
                request.writeInterfaceToken(DESCRIPTOR)
                request.write()
                if (!binder.transact(code, request, reply, 0)) {
                    return SomeIpHudTransactionResult.Failure(
                        SomeIpHudTransactionResult.Reason.TRANSACTION_REJECTED
                    )
                }
                reply.readException()
                // A truncated reply must not become a fabricated successful status (zero).
                if (reply.dataAvail() < Int.SIZE_BYTES) {
                    return SomeIpHudTransactionResult.Failure(
                        SomeIpHudTransactionResult.Reason.INVALID_REPLY
                    )
                }
                return SomeIpHudTransactionResult.Reply(reply.readInt())
            } finally {
                reply.recycle()
            }
        } catch (_: DeadObjectException) {
            return SomeIpHudTransactionResult.Failure(SomeIpHudTransactionResult.Reason.BINDER_DIED)
        } catch (_: RemoteException) {
            return SomeIpHudTransactionResult.Failure(SomeIpHudTransactionResult.Reason.REMOTE_EXCEPTION)
        } catch (_: SecurityException) {
            return SomeIpHudTransactionResult.Failure(SomeIpHudTransactionResult.Reason.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            return SomeIpHudTransactionResult.Failure(SomeIpHudTransactionResult.Reason.INVALID_REPLY)
        } finally {
            request.recycle()
        }
    }

    companion object {
        const val DESCRIPTOR = "ts.car.someip.sdk.ISomeIpServerInterface"
    }
}
