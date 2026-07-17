package com.byd.dashcast.fission;

import java.util.List;

/** Pure policy for deciding whether Layout owns startup and which saved layout it may launch. */
public final class LayoutAutoStartPolicy {

    private LayoutAutoStartPolicy() {}

    public static boolean isRequested(boolean fissionModeEnabled, boolean autoLayoutEnabled) {
        return fissionModeEnabled && autoLayoutEnabled;
    }

    /**
     * Returns the explicit usable favorite, or repairs an omitted/stale favorite only when there
     * is exactly one saved layout containing at least one bound application.
     */
    public static LayoutPreset chooseLayout(String favoriteId, List<LayoutPreset> presets) {
        if (presets == null || presets.isEmpty()) return null;

        LayoutPreset soleUsable = null;
        int usableCount = 0;
        for (LayoutPreset preset : presets) {
            if (!hasBoundApplication(preset)) continue;
            if (favoriteId != null && favoriteId.equals(preset.id)) return preset;
            soleUsable = preset;
            usableCount++;
        }
        return usableCount == 1 ? soleUsable : null;
    }

    public static boolean hasBoundApplication(LayoutPreset preset) {
        if (preset == null || preset.slots == null) return false;
        for (LayoutPreset.SlotDef slot : preset.slots) {
            if (slot != null && slot.packageName != null && !slot.packageName.isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
