package com.byd.dashcast.proxy;

import com.byd.dashcast.infrastructure.AdbLocalClient;

/** Pure retry-cooldown policy for classified self-ADB transport failures. */
final class ProxyTransportRetryPolicy {
    private ProxyTransportRetryPolicy() {}

    /**
     * XPORT_AUTH self-heals as soon as the driver accepts the popup, and XPORT_HANDSHAKE is a
     * transient transport race on a PROVEN-alive port — both get the short recheck so the
     * circuit-breaker does not lock the daemon out for a full dead-transport cooldown.
     */
    static long recheckMs(String transportState, long deadTransportMs, long authMs) {
        return (AdbLocalClient.XPORT_AUTH.equals(transportState)
                || AdbLocalClient.XPORT_HANDSHAKE.equals(transportState))
                ? authMs : deadTransportMs;
    }
}