package com.byd.dashcast.report

internal object ClusterShotSchedulePolicy {
    fun shouldCapture(clusterId: Int, nowMs: Long, lastCaptureMs: Long, intervalMs: Long): Boolean =
        clusterId > 0 && nowMs - lastCaptureMs >= intervalMs

    /**
     * Should the app issue its own prune of the shot directory?
     *
     * @param intervalMs     how often we are willing to app-prune at all.
     * @param daemonStaleMs  how old the daemon's own last prune must be, WHILE PROJECTING, before
     *   an app-side prune is worth its cost. Deliberately NOT the same number as [intervalMs] --
     *   both gates once shared one parameter and that conflation was a real, shipped defect: when
     *   the capture cadence was stretched to 90 s, a fixed 30 s staleness bound started passing
     *   between every pair of captures, firing ~80 app prunes per hour of projection where there
     *   had previously been zero. The daemon re-prunes after every successful capture, so a stamp
     *   older than one capture cycle plus a margin means captures are genuinely failing, which is
     *   the only time this earns its keep while projecting.
     * @param everCaptured   true once this process has attempted at least one capture round.
     *   A process that has never captured has nothing to prune, and every app-side prune costs a
     *   full ADB TCP probe + RSA handshake (AdbLocalClient.connect) to run a `cd ... || exit 0`
     *   against a directory that does not exist. The `clusterId <= 0` arm below used to fire
     *   unconditionally every 30 s for the entire life of a car that never projects: ~2 880
     *   handshakes/day to delete files that were never created. The daemon enforces the ring bound
     *   in-process after every write (SurfaceDaemon.pruneShotDir), so this is belt-and-braces.
     */
    fun shouldAppPrune(
        clusterId: Int,
        nowMs: Long,
        lastAppPruneMs: Long,
        lastDaemonPruneMs: Long,
        intervalMs: Long,
        daemonStaleMs: Long,
        everCaptured: Boolean
    ): Boolean {
        if (nowMs - lastAppPruneMs < intervalMs) return false
        if (!everCaptured) return false
        return clusterId <= 0 || nowMs - lastDaemonPruneMs >= daemonStaleMs
    }
}