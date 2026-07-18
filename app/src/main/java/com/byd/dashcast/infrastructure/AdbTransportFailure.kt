package com.byd.dashcast.infrastructure

import dadb.AdbAuthException
import dadb.AdbConnectException
import dadb.AdbTimeoutException
import java.net.ConnectException
import java.net.SocketTimeoutException

internal object AdbTransportFailure {
    fun classify(error: Throwable): String? {
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
                        else -> AdbLocalClient.XPORT_NO_LISTENER
                    }
                }
                is ConnectException -> return AdbLocalClient.XPORT_REFUSED
            }
            current = current.cause
        }
        return null
    }
}