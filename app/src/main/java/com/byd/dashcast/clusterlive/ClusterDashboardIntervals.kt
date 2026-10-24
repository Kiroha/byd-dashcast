package com.byd.dashcast.clusterlive

/** Shared monotonic intervals across opening, closing, recovery and helper replacement. */
internal class ClusterDashboardIntervals(private val clock: () -> Long, private val pause: (Long) -> Unit) {
    @Volatile private var nativeAfter = clock() + ClusterDashboardProfile.COMMAND_GAP_MS
    @Volatile private var rccAfter = clock() + ClusterDashboardProfile.RCC_SWITCH_GAP_MS
    fun nativeFinished() { nativeAfter = clock() + ClusterDashboardProfile.COMMAND_GAP_MS }
    fun rccFinished() { rccAfter = clock() + ClusterDashboardProfile.RCC_SWITCH_GAP_MS }
    fun rccRemaining(): Long = (rccAfter - clock()).coerceAtLeast(0)
    fun awaitNative(valid: () -> Boolean) = await({ nativeAfter }, valid)
    fun awaitRcc(valid: () -> Boolean) = await({ rccAfter }, valid)
    private fun await(deadline: () -> Long, valid: () -> Boolean) {
        while (clock() < deadline()) {
            check(valid()) { "Dashboard interval cancelled before admission" }
            pause(minOf(25, deadline() - clock()))
        }
        check(valid()) { "Dashboard request retired before admission" }
    }
}
