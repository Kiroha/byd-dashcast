package com.byd.dashcast.infrastructure

import dadb.AdbAuthException
import dadb.AdbConnectException
import dadb.AdbTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.ConnectException

class AdbTransportFailureTest {
    @Test
    fun `classifies typed timeout as unresponsive transport`() {
        assertEquals(
            AdbLocalClient.XPORT_UNRESPONSIVE,
            AdbTransportFailure.classify(AdbTimeoutException("read timed out"))
        )
    }

    @Test
    fun `classifies auth and refused connect distinctly`() {
        assertEquals(
            AdbLocalClient.XPORT_AUTH,
            AdbTransportFailure.classify(AdbAuthException("unauthorized"))
        )
        assertEquals(
            AdbLocalClient.XPORT_REFUSED,
            AdbTransportFailure.classify(
                AdbConnectException("connect failed", ConnectException("refused"))
            )
        )
    }

    @Test
    fun `accepted but silent handshake is unresponsive not closed`() {
        assertEquals(
            AdbLocalClient.XPORT_UNRESPONSIVE,
            AdbTransportFailure.classify(
                AdbConnectException("handshake failed", java.net.SocketTimeoutException("read"))
            )
        )
    }

    @Test
    fun `walks wrapped causes and ignores unrelated failures`() {
        assertEquals(
            AdbLocalClient.XPORT_UNRESPONSIVE,
            AdbTransportFailure.classify(IOException("wrapper", AdbTimeoutException("timeout")))
        )
        assertNull(AdbTransportFailure.classify(IllegalArgumentException("bad command")))
    }
}