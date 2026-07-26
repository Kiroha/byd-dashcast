package com.byd.dashcast.proxy

import com.byd.dashcast.infrastructure.AdbLocalClient

/** Pure retry-cooldown policy for classified self-ADB transport failures. */
object ProxyTransportRetryPolicy {
    /**
     * XPORT_AUTH self-heals as soon as the driver accepts the popup, and XPORT_HANDSHAKE is a
     * transient transport race on a PROVEN-alive port — both get the short recheck so the
     * circuit-breaker does not lock the daemon out for a full dead-transport cooldown.
     */
    @JvmStatic
    fun recheckMs(transportState: String?, deadTransportMs: Long, authMs: Long): Long {
        return if (AdbLocalClient.XPORT_AUTH == transportState ||
            AdbLocalClient.XPORT_HANDSHAKE == transportState) authMs else deadTransportMs
    }
}
