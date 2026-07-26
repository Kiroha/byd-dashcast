package com.byd.dashcast.report

internal object ClusterShotSchedulePolicy {
    fun shouldCapture(clusterId: Int, nowMs: Long, lastCaptureMs: Long, intervalMs: Long): Boolean =
        clusterId > 0 && nowMs - lastCaptureMs >= intervalMs

    fun shouldAppPrune(
        clusterId: Int,
        nowMs: Long,
        lastAppPruneMs: Long,
        lastDaemonPruneMs: Long,
        intervalMs: Long
    ): Boolean {
        if (nowMs - lastAppPruneMs < intervalMs) return false
        return clusterId <= 0 || nowMs - lastDaemonPruneMs >= intervalMs
    }
}