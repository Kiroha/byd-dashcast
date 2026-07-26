package com.byd.dashcast.proxy

/** Pure timing policy for the Binder wait that follows a proxy bootstrap attempt. */
object ProxyBootstrapPolicy {
    @JvmStatic
    fun binderWaitMs(bootstrapResult: String?, transportUnreachable: Boolean,
                     rebroadcastMs: Long, transportGraceMs: Long, coldSpawnMs: Long): Long {
        val result = bootstrapResult?.trim() ?: ""
        if (transportUnreachable) return transportGraceMs
        if (result.startsWith("REBROADCAST")) return rebroadcastMs
        return coldSpawnMs
    }
}
