package com.byd.dashcast.app

/**
 * Whether a boot broadcast is genuine enough to move stranded apps off the cluster.
 *
 * Lives apart from [BootReceiver] because it is the one decision in that receiver a wrong answer
 * makes visible to the driver, and it is not otherwise observable: this ROM re-delivers
 * BOOT_COMPLETED at every ACC-on WITHOUT rebooting (observed 25 min into an already-running
 * process on a 15-hour-old boot). Answer "genuine" there and the cleanup runs mid-session, yanking
 * the driver's projected app off the cluster; answer "re-delivery" on a real boot and last
 * session's app stays stranded on a VirtualDisplay that may still be alive.
 *
 * A pure function so both answers can be pinned without a car and without a boot.
 */
object BootCleanupPolicy {

    /**
     * Uptime ceiling below which a BOOT_COMPLETED is accepted as a genuine boot (AUD-006).
     *
     * `elapsedRealtime()` keeps counting from the last real boot, so a re-delivery reports hours
     * where a genuine boot reports seconds. 180 s leaves ample room for a slow head-unit boot while
     * staying orders of magnitude below the observed re-deliveries.
     */
    const val BOOT_CLEANUP_WINDOW_MS = 180_000L

    /**
     * @param isReplace ACTION_MY_PACKAGE_REPLACED. Exempt from the uptime test on purpose: our
     *   process was just replaced at an arbitrary uptime, and apps really can be stranded then.
     * @param uptimeMs SystemClock.elapsedRealtime(), read by the caller so this stays pure.
     */
    @JvmStatic
    fun shouldCleanup(isReplace: Boolean, uptimeMs: Long): Boolean =
        isReplace || uptimeMs <= BOOT_CLEANUP_WINDOW_MS
}
