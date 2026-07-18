package com.byd.dashcast.update;

/** Pure expiry policy for a persisted OTA relaunch request. */
final class OtaRelaunchPolicy {
    static final long MAX_AGE_MS = 30L * 60L * 1000L;

    private OtaRelaunchPolicy() {}

    static boolean shouldRelaunch(long requestedAtMs, long nowMs) {
        return requestedAtMs > 0L
                && nowMs >= requestedAtMs
                && nowMs - requestedAtMs <= MAX_AGE_MS;
    }
}