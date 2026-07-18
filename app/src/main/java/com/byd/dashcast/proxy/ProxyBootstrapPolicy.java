package com.byd.dashcast.proxy;

/** Pure timing policy for the Binder wait that follows a proxy bootstrap attempt. */
final class ProxyBootstrapPolicy {
    private ProxyBootstrapPolicy() {}

    static long binderWaitMs(String bootstrapResult, boolean transportUnreachable,
                             long rebroadcastMs, long transportGraceMs, long coldSpawnMs) {
        String result = bootstrapResult == null ? "" : bootstrapResult.trim();
        if (transportUnreachable) return transportGraceMs;
        if (result.startsWith("REBROADCAST")) return rebroadcastMs;
        return coldSpawnMs;
    }
}