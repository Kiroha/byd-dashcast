package com.byd.dashcast.proxy

import android.os.Binder
import android.os.IBinder

/** Process-lifetime token whose Binder death lets the SurfaceDaemon release transient mirror state. */
object MirrorResourceOwner {

    private val TOKEN: IBinder = Binder()

    @JvmStatic
    fun token(): IBinder = TOKEN
}
