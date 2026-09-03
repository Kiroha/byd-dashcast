package com.byd.dashcast.proxy.daemon;

/** Pure caller-identity policy shared by both privileged daemon Binder endpoints. */
final class DaemonCallerPolicy {

    private static final int SYSTEM_UID = 1000;
    private static final int PER_USER_RANGE = 100000;

    private DaemonCallerPolicy() {}

    static boolean isAllowed(int callingUid, int daemonUid, int appUid) {
        if (callingUid == SYSTEM_UID || callingUid == daemonUid) return true;
        if (appUid < 0 || callingUid < 0) return false;
        return (callingUid % PER_USER_RANGE) == (appUid % PER_USER_RANGE);
    }
}