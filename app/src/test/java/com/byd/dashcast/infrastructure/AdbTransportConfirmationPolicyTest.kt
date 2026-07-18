package com.byd.dashcast.infrastructure

import dadb.AdbAuthException
import dadb.AdbTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbTransportConfirmationPolicyTest {
    @Test
    fun `healthy independent echo suppresses stream timeout outage`() {
        assertNull(AdbTransportConfirmationPolicy.resolve(
            AdbLocalClient.XPORT_UNRESPONSIVE, true, null
        ))
    }

    @Test
    fun `failed independent probe confirms or refines transport state`() {
        assertEquals(
            AdbLocalClient.XPORT_UNRESPONSIVE,
            AdbTransportConfirmationPolicy.resolve(
                AdbLocalClient.XPORT_UNRESPONSIVE,
                false,
                AdbTimeoutException("second timeout")
            )
        )
        assertEquals(
            AdbLocalClient.XPORT_AUTH,
            AdbTransportConfirmationPolicy.resolve(
                AdbLocalClient.XPORT_UNRESPONSIVE,
                false,
                AdbAuthException("unauthorized")
            )
        )
    }

    @Test
    fun `live MirrorDaemon binder suppresses only stream-timeout diagnosis`() {
        assertFalse(AdbTransportConfirmationPolicy.shouldReportMirrorFailure(
            AdbLocalClient.XPORT_UNRESPONSIVE, true
        ))
        assertTrue(AdbTransportConfirmationPolicy.shouldReportMirrorFailure(
            AdbLocalClient.XPORT_AUTH, true
        ))
        assertTrue(AdbTransportConfirmationPolicy.shouldReportMirrorFailure(
            AdbLocalClient.XPORT_UNRESPONSIVE, false
        ))
    }

    @Test
    fun `newer successful command invalidates older failed confirmation`() {
        assertTrue(AdbTransportConfirmationPolicy.shouldApplyFailure(7L, 7L))
        assertFalse(AdbTransportConfirmationPolicy.shouldApplyFailure(7L, 8L))
    }
}