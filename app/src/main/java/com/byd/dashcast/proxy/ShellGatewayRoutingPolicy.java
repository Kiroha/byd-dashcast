package com.byd.dashcast.proxy;

/** Pure routing decision for the proxy/Binder and local-ADB shell paths. */
final class ShellGatewayRoutingPolicy {
    enum Route { PROXY, LEGACY, FAIL_FAST }

    private ShellGatewayRoutingPolicy() {}

    static Route select(boolean legacyPath, boolean proxyConnected,
                        boolean transportUnreachable) {
        if (!legacyPath && proxyConnected) return Route.PROXY;
        return transportUnreachable ? Route.FAIL_FAST : Route.LEGACY;
    }
}