package com.byd.dashcast.cluster.mirror;

/** Safety decision for injecting an event only after its destination was explicitly applied. */
public final class InputDisplayRoutingPolicy {

    private InputDisplayRoutingPolicy() {}

    public static boolean canInject(int displayId, boolean targetApplied) {
        return displayId > 0 && targetApplied;
    }
}