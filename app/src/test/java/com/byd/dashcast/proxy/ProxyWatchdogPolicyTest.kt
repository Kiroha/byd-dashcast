package com.byd.dashcast.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyWatchdogPolicyTest {

    @Test
    fun keeperSupersedesForegroundWatchdog() {
        assertFalse(ProxyWatchdog.shouldPoll(true, 1))
        assertFalse(ProxyWatchdog.shouldPoll(true, 3))
    }

    @Test
    fun watchdogRunsOnlyAsForegroundFallback() {
        assertTrue(ProxyWatchdog.shouldPoll(false, 1))
        assertFalse(ProxyWatchdog.shouldPoll(false, 0))
    }
}