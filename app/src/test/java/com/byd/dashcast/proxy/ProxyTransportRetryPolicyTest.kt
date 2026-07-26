package com.byd.dashcast.proxy

import com.byd.dashcast.infrastructure.AdbLocalClient
import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyTransportRetryPolicyTest {
    @Test
    fun `authorization retries quickly after popup approval`() {
        assertEquals(2_000L, ProxyTransportRetryPolicy.recheckMs(
            AdbLocalClient.XPORT_AUTH, 60_000, 2_000
        ))
    }

    @Test
    fun `unresponsive and closed transports retain storm protection`() {
        assertEquals(60_000L, ProxyTransportRetryPolicy.recheckMs(
            AdbLocalClient.XPORT_UNRESPONSIVE, 60_000, 2_000
        ))
        assertEquals(60_000L, ProxyTransportRetryPolicy.recheckMs(
            AdbLocalClient.XPORT_REFUSED, 60_000, 2_000
        ))
    }
}