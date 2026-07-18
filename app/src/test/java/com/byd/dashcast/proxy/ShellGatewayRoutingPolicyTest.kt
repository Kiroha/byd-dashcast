package com.byd.dashcast.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellGatewayRoutingPolicyTest {
    @Test
    fun `classified ADB failure does not hide a live proxy`() {
        assertEquals(
            ShellGatewayRoutingPolicy.Route.PROXY,
            ShellGatewayRoutingPolicy.select(false, true, true)
        )
    }

    @Test
    fun `classified ADB failure stops local fallback`() {
        assertEquals(
            ShellGatewayRoutingPolicy.Route.FAIL_FAST,
            ShellGatewayRoutingPolicy.select(false, false, true)
        )
        assertEquals(
            ShellGatewayRoutingPolicy.Route.FAIL_FAST,
            ShellGatewayRoutingPolicy.select(true, true, true)
        )
    }

    @Test
    fun `healthy ADB retains legacy fallback behavior`() {
        assertEquals(
            ShellGatewayRoutingPolicy.Route.LEGACY,
            ShellGatewayRoutingPolicy.select(false, false, false)
        )
        assertEquals(
            ShellGatewayRoutingPolicy.Route.LEGACY,
            ShellGatewayRoutingPolicy.select(true, true, false)
        )
    }
}