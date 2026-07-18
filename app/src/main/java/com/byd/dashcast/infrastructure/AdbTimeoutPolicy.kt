package com.byd.dashcast.infrastructure

internal object AdbTimeoutPolicy {
    fun effectiveIdleTimeoutMs(
        requestedMs: Int,
        operationAlreadySucceeded: Boolean,
        firstOperationMs: Int
    ): Int = if (operationAlreadySucceeded) requestedMs else maxOf(requestedMs, firstOperationMs)
}