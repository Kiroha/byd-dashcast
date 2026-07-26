package com.byd.dashcast.update

/** Pure expiry policy for a persisted OTA relaunch request. */
object OtaRelaunchPolicy {
    const val MAX_AGE_MS = 30L * 60L * 1000L

    @JvmStatic
    fun shouldRelaunch(requestedAtMs: Long, nowMs: Long): Boolean {
        return requestedAtMs > 0L &&
            nowMs >= requestedAtMs &&
            nowMs - requestedAtMs <= MAX_AGE_MS
    }
}
