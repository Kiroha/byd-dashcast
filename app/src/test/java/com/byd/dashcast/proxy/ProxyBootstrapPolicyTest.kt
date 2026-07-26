package com.byd.dashcast.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyBootstrapPolicyTest {
    @Test
    fun `transport failure uses only binder grace`() {
        assertEquals(2_000L, ProxyBootstrapPolicy.binderWaitMs(
            "ERR ADB read timed out", true, 5_000, 2_000, 15_000
        ))
    }

    @Test
    fun `rebroadcast is shorter than cold spawn`() {
        assertEquals(5_000L, ProxyBootstrapPolicy.binderWaitMs(
            "REBROADCAST 123", false, 5_000, 2_000, 15_000
        ))
        assertEquals(15_000L, ProxyBootstrapPolicy.binderWaitMs(
            "OK /data/app/base.apk", false, 5_000, 2_000, 15_000
        ))
    }
}