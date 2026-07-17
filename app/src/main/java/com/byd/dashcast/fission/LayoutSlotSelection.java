package com.byd.dashcast.fission;

import java.util.List;

/** Pure ordered-selection policy for switching the tactile mirror between Layout slots. */
public final class LayoutSlotSelection {

    private LayoutSlotSelection() {}

    public static String resolve(String selectedPackage, List<String> orderedPackages) {
        if (orderedPackages == null || orderedPackages.isEmpty()) return null;
        if (selectedPackage != null && orderedPackages.contains(selectedPackage)) {
            return selectedPackage;
        }
        return orderedPackages.get(0);
    }

    public static String step(String selectedPackage, List<String> orderedPackages, int delta) {
        if (orderedPackages == null || orderedPackages.isEmpty()) return null;
        String resolved = resolve(selectedPackage, orderedPackages);
        int index = orderedPackages.indexOf(resolved);
        int size = orderedPackages.size();
        int next = ((index + delta) % size + size) % size;
        return orderedPackages.get(next);
    }
}
