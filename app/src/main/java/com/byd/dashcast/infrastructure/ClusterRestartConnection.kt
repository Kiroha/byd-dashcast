package com.byd.dashcast.infrastructure

import java.io.Closeable

/** Closing or losing this connection never establishes physical restoration. */
internal interface ClusterRestartConnection : Closeable {
    fun dispatch()
}
