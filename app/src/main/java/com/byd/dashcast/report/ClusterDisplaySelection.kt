package com.byd.dashcast.report

internal object ClusterDisplaySelection {
    fun choose(presentationIds: IntArray, allIds: IntArray): Int {
        presentationIds.firstOrNull { it > 0 }?.let { return it }
        return allIds.firstOrNull { it > 0 } ?: -1
    }
}