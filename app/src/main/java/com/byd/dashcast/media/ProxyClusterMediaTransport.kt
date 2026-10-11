package com.byd.dashcast.media

import android.os.IBinder
import com.byd.dashcast.proxy.ProxyClient

/** Reuses the existing instrument byte/int verbs, never the navigation path or daemon bootstrap. */
internal class ProxyClusterMediaTransport : ClusterMediaTransport {
    override fun connection(): Any? = ProxyClient.dispatchBinder()?.takeIf { it.isBinderAlive }
    override fun isCurrent(connection: Any): Boolean =
        connection is IBinder && connection.isBinderAlive && ProxyClient.dispatchBinder() === connection

    override fun integer(connection: Any, property: Int, value: Int): Int {
        require((property == ClusterMediaProperties.SOURCE && value == ClusterMediaProperties.ONLINE) ||
            (property == ClusterMediaProperties.STATE && value in 1..2))
        return ProxyClient.canInstrumentIntOnCurrentBinder(connection as IBinder, property, value)
    }

    override fun bytes(connection: Any, property: Int, value: ByteArray): Int {
        require(property == ClusterMediaProperties.TEXT && value.size in 8..254 && value.size % 2 == 0)
        return ProxyClient.canInstrumentBytesOnCurrentBinder(connection as IBinder, property, value)
    }
}
