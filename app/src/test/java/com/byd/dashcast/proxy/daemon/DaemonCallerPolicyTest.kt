package com.byd.dashcast.proxy.daemon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DaemonCallerPolicyTest {

    @Test
    fun `an unresolved app uid never authorizes an app caller`() {
        assertFalse(DaemonCallerPolicy.isAllowed(10167, 2000, -1))
    }

    @Test
    fun `system and daemon remain available while app identity is unresolved`() {
        assertTrue(DaemonCallerPolicy.isAllowed(1000, 2000, -1))
        assertTrue(DaemonCallerPolicy.isAllowed(2000, 2000, -1))
    }

    @Test
    fun `the app and the same app id in another user are accepted`() {
        assertTrue(DaemonCallerPolicy.isAllowed(10167, 2000, 10167))
        assertTrue(DaemonCallerPolicy.isAllowed(110167, 2000, 10167))
    }

    @Test
    fun `another app is rejected`() {
        assertFalse(DaemonCallerPolicy.isAllowed(10168, 2000, 10167))
    }
}