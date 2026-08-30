package com.byd.dashcast.cluster.display

/** Selects only a usable cluster display from app- or daemon-visible candidates. */
object ClusterDisplaySelectionPolicy {

    @JvmStatic
    fun pick(displays: List<ClusterDisplayInfo>): ClusterDisplayInfo? {
        var bestNamed: ClusterDisplayInfo? = null
        var fallback: ClusterDisplayInfo? = null
        for (display in displays) {
            if (display.id == 0 || display.isPrivate || display.isThirdPartyOwned()) continue
            if (ClusterDisplayNames.isKnownClusterName(display.name)) {
                if (bestNamed == null || ClusterDisplayNames.clusterNamePriority(display.name) <
                    ClusterDisplayNames.clusterNamePriority(bestNamed.name)
                ) {
                    bestNamed = display
                }
            } else if (fallback == null && !display.isStateOff()) {
                fallback = display
            }
        }
        return bestNamed ?: fallback
    }
}