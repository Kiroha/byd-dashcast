package com.byd.dashcast.proxy;

import com.byd.dashcast.infrastructure.AdbLocalClient;

/** Pure retry-cooldown policy for classified self-ADB transport failures. */
final class ProxyTransportRetryPolicy {
    private ProxyTransportRetryPolicy() {}

    static long recheckMs(String transportState, long deadTransportMs, long authMs) {
        return AdbLocalClient.XPORT_AUTH.equals(transportState) ? authMs : deadTransportMs;
    }
}