package com.byd.dashcast.proxy

/** Pure routing decision for the proxy/Binder and local-ADB shell paths. */
object ShellGatewayRoutingPolicy {
    enum class Route { PROXY, LEGACY, FAIL_FAST }

    @JvmStatic
    fun select(legacyPath: Boolean, proxyConnected: Boolean, transportUnreachable: Boolean): Route {
        if (!legacyPath && proxyConnected) return Route.PROXY
        return if (transportUnreachable) Route.FAIL_FAST else Route.LEGACY
    }
}
