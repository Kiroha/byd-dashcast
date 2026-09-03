package com.byd.dashcast.proxy

import android.os.Binder
import android.os.IBinder

/** Process-owned token for transient VirtualDisplays created by ProxyDaemon. */
internal object ProxyDisplayResourceOwner {

    private val TOKEN: IBinder = Binder()

    @JvmStatic
    fun token(): IBinder = TOKEN
}
