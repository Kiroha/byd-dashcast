package com.byd.dashcast.ui.main;

import java.util.Set;

/** Shared active-indicator policy for app-grid rows and favourite-strip tiles. */
public final class AppProjectionIndicator {

    private AppProjectionIndicator() {}

    public static boolean isActive(String packageName, String clusterPackage,
                                   String mainPackage, Set<String> layoutPackages) {
        if (packageName == null) return false;
        return packageName.equals(clusterPackage)
                || packageName.equals(mainPackage)
                || (layoutPackages != null && layoutPackages.contains(packageName));
    }
}
