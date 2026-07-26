package com.byd.dashcast.fission

/** Pure ordered-selection policy for switching the tactile mirror between Layout slots. */
object LayoutSlotSelection {

    @JvmStatic
    fun resolve(selectedPackage: String?, orderedPackages: List<String>?): String? {
        if (orderedPackages.isNullOrEmpty()) return null
        if (selectedPackage != null && orderedPackages.contains(selectedPackage)) {
            return selectedPackage
        }
        return orderedPackages[0]
    }

    @JvmStatic
    fun step(selectedPackage: String?, orderedPackages: List<String>?, delta: Int): String? {
        if (orderedPackages.isNullOrEmpty()) return null
        val resolved = resolve(selectedPackage, orderedPackages)
        val index = orderedPackages.indexOf(resolved)
        val size = orderedPackages.size
        val next = ((index + delta) % size + size) % size
        return orderedPackages[next]
    }
}
