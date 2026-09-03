package com.byd.dashcast.cluster.display

/**
 * Pure rule for the DiLink 5 cluster layerStack / displayId indirection.
 *
 * On DiLink 5 the app is launched onto a *shadow* render display (`shared_fission_bg_
 * XDJAScreenProjection_0/_1`, displayId 3/4, layerStack 3/4), whose content the OEM container
 * composites onto `fission_bg_XDJAScreenProjection` (displayId 2, layerStack 2) — and layerStack 2
 * is what actually reaches the panel. Anything that wants to *read* the cluster face (mirror,
 * screenshot) or to *inject touch* into it must therefore address 2, not 3/4.
 *
 * Extracted so the mirror and the screenshot recorder cannot drift: they were already inconsistent
 * — the mirror applied the override, the screenshot recorder did not, so every cluster screenshot
 * from a DiLink 5 car was an all-black frame of the (legitimately empty) shadow layerStack, which
 * then got mis-read as "the panel was black" (INC-20260804-171617).
 *
 * Deliberately takes a plain [Boolean] rather than a Context: the platform lookup (and its
 * fail-open error handling) stays at the call site, and the numeric rule stays unit-testable.
 */
object ClusterLayerStackPolicy {

    /** The composed cluster face on DiLink 5 — the only layerStack/displayId that reaches the panel. */
    const val DL5_COMPOSED = 2

    /** The shadow render displays a DiLink 5 app is launched onto. */
    private val DL5_SHADOW = intArrayOf(3, 4)

    /**
     * Rewrites a DiLink 5 shadow value (3/4) to the composed cluster face (2).
     *
     * Returns [value] unchanged when [isDiLink5] is false, or when the value is anything other than
     * 3/4 — so DiLink 3 (layerStack 1), DiLink 4 (layerStack 1) and trinket/DiLink 5.1 (which
     * projects onto the plain `fission_bg_XDJAScreenProjection`, layerStack 2) are all untouched
     * even though trinket reports as DiLink 5.
     */
    @JvmStatic
    fun composedOrSelf(isDiLink5: Boolean, value: Int): Int {
        if (!isDiLink5) return value
        for (shadow in DL5_SHADOW) {
            if (value == shadow) return DL5_COMPOSED
        }
        return value
    }
}
