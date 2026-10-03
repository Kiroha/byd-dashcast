package com.byd.dashcast.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread

internal enum class SomeIpHudBindResult {
    REQUESTED,
    REJECTED,
    PERMISSION_DENIED,
    FAILED,
    CANCELLED
}

/**
 * Inactive SOME/IP transport foundation; no production HUD route constructs this class yet.
 *
 * The session owner must serialize bind/transactions on its worker. Android callbacks only publish
 * connection state: descriptor lookup, death registration and all OEM transactions happen on the
 * calling worker. [close] may invalidate ownership immediately from any thread, even while Binder
 * is blocked; an already-running synchronous transaction cannot be interrupted.
 *
 * Each bind owns a distinct connection. Late callbacks cannot republish a stopped/replaced binder.
 * Android may reconnect a disconnected binding; binding death/null binding/remote death retire it.
 * A new bind must be requested by the owner after checking that its source/session is still valid.
 * No cached navigation, profile guessing, automatic replay or transport executor is introduced.
 *
 * Closing releases the binding and death recipient. It deliberately does not issue TX5: stopping
 * an OEM service may affect native navigation, so the profile owner must explicitly choose which
 * services it is allowed to stop once the receiver contract has been verified.
 */
internal class SomeIpHudTransport(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var connection: Connection? = null

    /** True means a binder was supplied, not that its interface/profile/ACL is compatible. */
    val isBinderAvailable: Boolean
        get() = synchronized(lock) { connection?.binder != null }

    /** An accepted bind request is asynchronous and does not establish receiver compatibility. */
    @WorkerThread
    fun bind(): SomeIpHudBindResult {
        val pending = synchronized(lock) {
            if (connection != null) return SomeIpHudBindResult.REQUESTED
            Connection().also { connection = it }
        }
        val accepted: Boolean
        val result: SomeIpHudBindResult
        try {
            accepted = appContext.bindService(
                Intent(ACTION).setComponent(ComponentName(PACKAGE, SERVICE)),
                pending,
                Context.BIND_AUTO_CREATE
            )
            result = if (accepted) SomeIpHudBindResult.REQUESTED else SomeIpHudBindResult.REJECTED
        } catch (_: SecurityException) {
            finishBinding(pending, false)
            return SomeIpHudBindResult.PERMISSION_DENIED
        } catch (_: RuntimeException) {
            finishBinding(pending, false)
            return SomeIpHudBindResult.FAILED
        }
        finishBinding(pending, accepted)
        return if (accepted && synchronized(lock) { connection !== pending }) {
            SomeIpHudBindResult.CANCELLED
        } else {
            result
        }
    }

    @WorkerThread
    fun startService(serviceId: Long): SomeIpHudTransactionResult = withBinder {
        startService(serviceId)
    }

    @WorkerThread
    fun stopService(serviceId: Long): SomeIpHudTransactionResult = withBinder {
        stopService(serviceId)
    }

    @WorkerThread
    fun fireEvent(topicId: Long, payload: ByteArray): SomeIpHudTransactionResult = withBinder {
        fireEvent(topicId, payload)
    }

    @AnyThread
    override fun close() {
        val current = synchronized(lock) { connection } ?: return
        retire(current)
    }

    private fun finishBinding(pending: Connection, accepted: Boolean) {
        val detach = synchronized(lock) {
            pending.bindingFinished = true
            // Context requires unbind even when bindService returns false. An exception can also
            // follow local registration; unbind tolerates Android having no registration left.
            // https://developer.android.com/reference/android/content/Context#bindService(android.content.Intent,%20android.content.ServiceConnection,%20int)
            pending.registered = true
            if (!accepted) {
                if (connection === pending) connection = null
                pending.cancelled = true
                pending.binder = null
            }
            claimUnbind(pending)
        }
        if (detach) unbind(pending)
    }

    private fun withBinder(
        call: SomeIpHudBinderProtocol.() -> SomeIpHudTransactionResult
    ): SomeIpHudTransactionResult {
        val current: Connection
        val binder: IBinder
        synchronized(lock) {
            current = connection ?: return failure(SomeIpHudTransactionResult.Reason.NOT_CONNECTED)
            binder = current.binder ?: return failure(SomeIpHudTransactionResult.Reason.NOT_CONNECTED)
        }
        try {
            if (synchronized(lock) { current.watchedBinder !== binder }) {
                if (binder.interfaceDescriptor != SomeIpHudBinderProtocol.DESCRIPTOR) {
                    retire(current, binder)
                    return failure(SomeIpHudTransactionResult.Reason.INVALID_INTERFACE)
                }
                val recipient = IBinder.DeathRecipient { retire(current, binder) }
                binder.linkToDeath(recipient, 0)
                val adopted = synchronized(lock) {
                    if (!isCurrent(current, binder)) false else {
                        current.watchedBinder = binder
                        current.deathRecipient = recipient
                        true
                    }
                }
                if (!adopted) {
                    unlink(binder, recipient)
                    return failure(SomeIpHudTransactionResult.Reason.SESSION_CLOSED)
                }
            }
        } catch (_: DeadObjectException) {
            retire(current, binder)
            return failure(SomeIpHudTransactionResult.Reason.BINDER_DIED)
        } catch (_: RemoteException) {
            retire(current, binder)
            return failure(SomeIpHudTransactionResult.Reason.REMOTE_EXCEPTION)
        } catch (_: SecurityException) {
            retire(current, binder)
            return failure(SomeIpHudTransactionResult.Reason.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            retire(current, binder)
            return failure(SomeIpHudTransactionResult.Reason.INVALID_INTERFACE)
        }
        if (!synchronized(lock) { isCurrent(current, binder) }) {
            return failure(SomeIpHudTransactionResult.Reason.SESSION_CLOSED)
        }
        val result = SomeIpHudBinderProtocol(binder).call()
        // A reply from an invalidated session must never refresh the owner's delivery/liveness.
        if (!synchronized(lock) { isCurrent(current, binder) }) {
            return failure(SomeIpHudTransactionResult.Reason.SESSION_CLOSED)
        }
        if (result is SomeIpHudTransactionResult.Failure &&
            (result.reason == SomeIpHudTransactionResult.Reason.BINDER_DIED ||
                result.reason == SomeIpHudTransactionResult.Reason.PERMISSION_DENIED)) {
            retire(current, binder)
        }
        return result
    }

    /** All state checks/mutations use lock; no Binder operation holds it. */
    private fun isCurrent(current: Connection, binder: IBinder): Boolean =
        connection === current && !current.cancelled && current.binder === binder

    private fun retire(current: Connection, expectedBinder: IBinder? = null) {
        val watched: IBinder?
        val recipient: IBinder.DeathRecipient?
        val detach: Boolean
        synchronized(lock) {
            if (expectedBinder != null && !isCurrent(current, expectedBinder)) return
            if (connection === current) connection = null
            current.cancelled = true
            current.binder = null
            watched = current.watchedBinder
            recipient = current.deathRecipient
            current.watchedBinder = null
            current.deathRecipient = null
            detach = claimUnbind(current)
        }
        if (watched != null && recipient != null) unlink(watched, recipient)
        if (detach) unbind(current)
    }

    private fun claimUnbind(current: Connection): Boolean {
        if (!current.cancelled || !current.bindingFinished || !current.registered) return false
        current.registered = false
        return true
    }

    private fun unbind(current: Connection) {
        try {
            appContext.unbindService(current)
        } catch (_: IllegalArgumentException) {
            // Android has already released this registration.
        } catch (_: SecurityException) {
            // Rights can be revoked while an asynchronous bind is in progress.
        }
    }

    private fun unlink(binder: IBinder, recipient: IBinder.DeathRecipient) {
        try {
            binder.unlinkToDeath(recipient, 0)
        } catch (_: NoSuchElementException) {
            // Death may have already removed the recipient.
        }
    }

    private inner class Connection : ServiceConnection {
        var bindingFinished = false
        var registered = false
        var cancelled = false
        var binder: IBinder? = null
        var watchedBinder: IBinder? = null
        var deathRecipient: IBinder.DeathRecipient? = null

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val watched: IBinder?
            val recipient: IBinder.DeathRecipient?
            synchronized(lock) {
                if (connection !== this || cancelled || binder === service) return
                watched = watchedBinder
                recipient = deathRecipient
                watchedBinder = null
                deathRecipient = null
                binder = service
            }
            if (watched != null && recipient != null) unlink(watched, recipient)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            val watched: IBinder?
            val recipient: IBinder.DeathRecipient?
            synchronized(lock) {
                watched = watchedBinder
                recipient = deathRecipient
                binder = null
                watchedBinder = null
                deathRecipient = null
            }
            if (watched != null && recipient != null) unlink(watched, recipient)
        }

        override fun onBindingDied(name: ComponentName) = retire(this)

        override fun onNullBinding(name: ComponentName) = retire(this)
    }

    companion object {
        const val ACTION = "com.ts.car.someip.SomeIpServerService"
        const val PACKAGE = "com.ts.car.someip.service"
        const val SERVICE = "com.ts.car.someip.service.manager.SomeIpServerService"

        private fun failure(reason: SomeIpHudTransactionResult.Reason) =
            SomeIpHudTransactionResult.Failure(reason)
    }
}
