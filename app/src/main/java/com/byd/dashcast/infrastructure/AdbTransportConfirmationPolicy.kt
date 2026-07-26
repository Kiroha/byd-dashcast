package com.byd.dashcast.infrastructure

/** Pure policy separating one stuck ADB stream from a confirmed transport outage. */
internal object AdbTransportConfirmationPolicy {
    fun resolve(originalState: String?, probeSucceeded: Boolean, probeError: Throwable?): String? {
        if (originalState != AdbLocalClient.XPORT_UNRESPONSIVE) return originalState
        if (probeSucceeded) return null
        return AdbTransportFailure.classify(probeError ?: IllegalStateException("bad echo"))
            ?: AdbLocalClient.XPORT_UNRESPONSIVE
    }

    fun shouldReportMirrorFailure(state: String?, binderAlive: Boolean): Boolean =
        state != AdbLocalClient.XPORT_UNRESPONSIVE || !binderAlive

    fun shouldApplyFailure(successGenerationAtStart: Long, currentSuccessGeneration: Long): Boolean =
        successGenerationAtStart == currentSuccessGeneration
}