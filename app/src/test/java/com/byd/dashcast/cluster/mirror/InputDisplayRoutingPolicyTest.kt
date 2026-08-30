package com.byd.dashcast.cluster.mirror

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputDisplayRoutingPolicyTest {

    @Test
    fun `a positive display with an applied target may be injected`() {
        assertTrue(InputDisplayRoutingPolicy.canInject(3, true))
    }

    @Test
    fun `missing or failed targeting never falls through to global focus`() {
        assertFalse(InputDisplayRoutingPolicy.canInject(3, false))
        assertFalse(InputDisplayRoutingPolicy.canInject(0, true))
        assertFalse(InputDisplayRoutingPolicy.canInject(-1, true))
    }
}