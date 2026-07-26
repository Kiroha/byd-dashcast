package com.byd.dashcast.infrastructure

import dadb.AdbAuthException
import dadb.AdbConnectException
import dadb.AdbTimeoutException
import java.net.ConnectException
import java.net.SocketTimeoutException

internal object AdbTransportFailure {
    /**
     * Maps a local-ADB failure onto one of the `AdbLocalClient.XPORT_*` states.
     *
     * @param tcpProbePassed `true` when a plain TCP connect to adbd's port succeeded
     *   milliseconds before this failure. The listener is then PROVEN alive, so an ADB-level
     *   connect failure must never be reported as [AdbLocalClient.XPORT_NO_LISTENER] — doing so
     *   told the tester to run `adb tcpip 5555` for a port that had answered 429 ms earlier
     *   (dadb raises `AdbConnectException("Connection handshake failed")` when adbd tears down
     *   colliding transports). Such a failure is a transient handshake problem
     *   ([AdbLocalClient.XPORT_HANDSHAKE]) that the connect path retries on its own.
     */
    @JvmOverloads
    fun classify(error: Throwable, tcpProbePassed: Boolean = false): String? {
        var current: Throwable? = error
        while (current != null) {
            when (current) {
                is AdbAuthException -> return AdbLocalClient.XPORT_AUTH
                is AdbTimeoutException, is SocketTimeoutException ->
                    return AdbLocalClient.XPORT_UNRESPONSIVE
                is AdbConnectException -> {
                    val cause = current.cause
                    return when (cause) {
                        is ConnectException -> AdbLocalClient.XPORT_REFUSED
                        is SocketTimeoutException -> AdbLocalClient.XPORT_UNRESPONSIVE
                        else ->
                            if (tcpProbePassed) AdbLocalClient.XPORT_HANDSHAKE
                            else AdbLocalClient.XPORT_NO_LISTENER
                    }
                }
                is ConnectException -> return AdbLocalClient.XPORT_REFUSED
            }
            current = current.cause
        }
        return null
    }
}