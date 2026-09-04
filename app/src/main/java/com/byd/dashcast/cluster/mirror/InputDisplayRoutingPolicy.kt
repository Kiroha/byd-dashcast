package com.byd.dashcast.cluster.mirror

/** Safety decision for injecting an event only after its destination was explicitly applied. */
object InputDisplayRoutingPolicy {

    @JvmStatic
    fun canInject(displayId: Int, targetApplied: Boolean): Boolean {
        return displayId > 0 && targetApplied
    }
}
