package com.byd.dashcast.fission

/** Pure policy for deciding whether Layout owns startup and which saved layout it may launch. */
object LayoutAutoStartPolicy {

    @JvmStatic
    fun isRequested(fissionModeEnabled: Boolean, autoLayoutEnabled: Boolean): Boolean {
        return fissionModeEnabled && autoLayoutEnabled
    }

    /**
     * Returns the explicit usable favorite, or repairs an omitted/stale favorite only when there
     * is exactly one saved layout containing at least one bound application.
     */
    @JvmStatic
    fun chooseLayout(favoriteId: String?, presets: List<LayoutPreset>?): LayoutPreset? {
        if (presets.isNullOrEmpty()) return null

        var soleUsable: LayoutPreset? = null
        var usableCount = 0
        for (preset in presets) {
            if (!hasBoundApplication(preset)) continue
            if (favoriteId != null && favoriteId == preset.id) return preset
            soleUsable = preset
            usableCount++
        }
        return if (usableCount == 1) soleUsable else null
    }

    @JvmStatic
    fun hasBoundApplication(preset: LayoutPreset?): Boolean {
        val slots = preset?.slots ?: return false
        for (slot in slots) {
            val pkg = slot?.packageName
            if (!pkg.isNullOrEmpty()) return true
        }
        return false
    }
}
