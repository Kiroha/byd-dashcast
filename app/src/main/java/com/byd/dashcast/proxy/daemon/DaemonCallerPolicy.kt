package com.byd.dashcast.proxy.daemon

/** Pure caller-identity policy shared by both privileged daemon Binder endpoints. */
internal object DaemonCallerPolicy {

    private const val SYSTEM_UID = 1000
    private const val PER_USER_RANGE = 100000

    @JvmStatic
    fun isAllowed(callingUid: Int, daemonUid: Int, appUid: Int): Boolean {
        if (callingUid == SYSTEM_UID || callingUid == daemonUid) return true
        if (appUid < 0 || callingUid < 0) return false
        return (callingUid % PER_USER_RANGE) == (appUid % PER_USER_RANGE)
    }
}
