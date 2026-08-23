package com.byd.dashcast.report

internal object ClusterShotSchedulePolicy {
    fun shouldCapture(clusterId: Int, nowMs: Long, lastCaptureMs: Long, intervalMs: Long): Boolean =
        clusterId > 0 && nowMs - lastCaptureMs >= intervalMs

    /**
     * @param everCaptured true once this process has attempted at least one capture round.
     *
     * AUD-PERF-P2 — a process that has never captured has nothing to prune, and every app-side
     * prune costs a full ADB TCP probe + RSA handshake (AdbLocalClient.connect) to run a
     * `cd ... || exit 0` against a directory that does not exist. The `clusterId <= 0` branch
     * below used to make this fire unconditionally every 30 s for the entire life of a car that
     * never projects: ~2 880 handshakes/day to delete files that were never created. The daemon
     * enforces the ring bound in-process after every write (SurfaceDaemon.pruneShotDir), so the
     * app-side prune is belt-and-braces, not the sole bound.
     */
    fun shouldAppPrune(
        clusterId: Int,
        nowMs: Long,
        lastAppPruneMs: Long,
        lastDaemonPruneMs: Long,
        intervalMs: Long,
        everCaptured: Boolean
    ): Boolean {
        if (nowMs - lastAppPruneMs < intervalMs) return false
        if (!everCaptured) return false
        return clusterId <= 0 || nowMs - lastDaemonPruneMs >= intervalMs
    }
}