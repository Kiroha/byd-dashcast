package com.byd.dashcast.cluster

/** Holds classic launches until every already-submitted eviction workflow is terminal. */
internal class EvictionLaunchFence {
    private var activeOperations = 0
    private val deferred = LinkedHashMap<String, Runnable>()

    @Synchronized
    fun beginOperation() {
        activeOperations++
    }

    /** Returns true when the caller may run now; otherwise retains the latest package request. */
    @Synchronized
    fun prepareLaunch(packageName: String, launch: Runnable): Boolean {
        if (activeOperations == 0) return true
        deferred[packageName] = launch
        return false
    }

    /** Runs released dispatches under this monitor, so a concurrent begin cannot overtake them. */
    @Synchronized
    fun finishOperation() {
        check(activeOperations > 0)
        activeOperations--
        if (activeOperations != 0) return
        val launches = deferred.values.toList()
        deferred.clear()
        for (launch in launches) {
            try {
                launch.run()
            } catch (_: Exception) {}
        }
    }
}