package com.byd.dashcast.ime

/** Process-local identity of the editable field session opened on the cluster. */
internal class ClusterImeRelaySession {

    private var displayId = -1
    private var packageName: String? = null

    @Synchronized
    fun bind(targetDisplayId: Int, targetPackage: String?) {
        if (targetDisplayId <= 0 || targetPackage == null || targetPackage.isEmpty()) {
            clear()
            return
        }
        displayId = targetDisplayId
        packageName = targetPackage
    }

    @Synchronized
    fun clear() {
        displayId = -1
        packageName = null
    }

    @Synchronized
    fun hasTargetOn(activeDisplayId: Int): Boolean {
        return activeDisplayId > 0 && displayId == activeDisplayId && packageName != null
    }

    @Synchronized
    fun packageOn(activeDisplayId: Int): String? {
        return if (hasTargetOn(activeDisplayId)) packageName else null
    }

    @Synchronized
    fun accepts(activeDisplayId: Int, candidatePackage: CharSequence?): Boolean {
        if (!hasTargetOn(activeDisplayId)) return false
        if (candidatePackage == null) return false
        // The bound package is read into a NON-NULL local on purpose. Kotlin's
        // CharSequence?.contentEquals extension returns true when both sides are null, which would
        // turn this fail-closed gate into a fail-open one.
        val boundPackage = packageName ?: return false
        return boundPackage.contentEquals(candidatePackage)
    }
}
