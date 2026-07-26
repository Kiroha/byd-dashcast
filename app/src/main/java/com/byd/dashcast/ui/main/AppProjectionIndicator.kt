package com.byd.dashcast.ui.main

/** Shared active-indicator policy for app-grid rows and favourite-strip tiles. */
object AppProjectionIndicator {

    @JvmStatic
    fun isActive(packageName: String?, clusterPackage: String?,
                 mainPackage: String?, layoutPackages: Set<String>?): Boolean {
        if (packageName == null) return false
        return packageName == clusterPackage ||
            packageName == mainPackage ||
            (layoutPackages != null && layoutPackages.contains(packageName))
    }
}
