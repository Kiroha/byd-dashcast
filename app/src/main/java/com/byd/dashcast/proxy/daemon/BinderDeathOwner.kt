package com.byd.dashcast.proxy.daemon

import android.os.IBinder
import android.os.RemoteException

import com.byd.dashcast.util.concurrent.DeathLease

/** Adapts an app-owned Binder token to the platform-independent DeathLease contract. */
internal class BinderDeathOwner(private val binder: IBinder) : DeathLease.Owner {

    private var recipient: IBinder.DeathRecipient? = null
    private var callback: Runnable? = null

    @Synchronized
    @Throws(RemoteException::class)
    override fun link(deathCallback: Runnable) {
        check(recipient == null) { "owner already linked" }
        callback = deathCallback
        val r = IBinder.DeathRecipient {
            val current: Runnable?
            synchronized(this@BinderDeathOwner) {
                current = callback
                callback = null
                recipient = null
            }
            current?.run()
        }
        recipient = r
        binder.linkToDeath(r, 0)
    }

    @Synchronized
    override fun unlink(deathCallback: Runnable) {
        val current = recipient
        if (callback != deathCallback || current == null) return
        callback = null
        recipient = null
        try {
            binder.unlinkToDeath(current, 0)
        } catch (ignored: Throwable) {
        }
    }
}
