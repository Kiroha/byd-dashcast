package com.byd.dashcast.cluster.display

/**
 * The one rule about cluster shape commands, in one place.
 *
 * `sendInfo(1000, 29|30|31)` asks the OEM to switch the instrument cluster to a geometry preset:
 * 29 = 8.8" (Atto 3, Dolphin — 1280x480), 30 = 12.3" (Seal EU — 1920x720), 31 = 10.25".
 *
 * **On a 1280x480 panel, 30 and 31 break the cluster.** Owners of those cars sent the maintainer
 * photographs of their instrument cluster dropping into its degraded "simple mode" after being
 * pushed to a larger shape. That is a vehicle-level symptom — worse than any projection failure —
 * so it is refused, whatever asked for it.
 *
 * This exists as a policy object rather than an `if` at each call site because there are four
 * senders and two of them were missed the first time: the ADAS window fix (activation, warm and
 * slow paths) was guarded, while `restoreOriginCluster` — the DEFAULT Stop flow, which passes the
 * raw preference and whose default is 30 — was not. Of the three known simple-mode reports, two
 * came through that unguarded Stop path with the ADAS fix off entirely
 * (INC-20260625-173900, INC-20260715-141429), and only one through the guarded one
 * (INC-20260720-073031).
 *
 * Note the asymmetry: 29 is always allowed. Shrinking to the native small shape is what those cars
 * want; only growing past it is destructive.
 */
object ClusterGeometryPolicy {

    const val CMD_8_8 = 29
    const val CMD_12_3 = 30
    const val CMD_10_25 = 31

    /** The panel geometry proven to break under a larger preset. */
    const val SMALL_PANEL_W = 1280
    const val SMALL_PANEL_H = 480

    /** True for the three cluster-geometry presets, and only those. */
    @JvmStatic
    fun isShapeCommand(infoInt: Int): Boolean =
        infoInt == CMD_8_8 || infoInt == CMD_12_3 || infoInt == CMD_10_25

    /** True when [width] x [height] is the small panel, in either orientation. */
    @JvmStatic
    fun isSmallPanelGeometry(width: Int, height: Int): Boolean =
        (width == SMALL_PANEL_W && height == SMALL_PANEL_H) ||
            (width == SMALL_PANEL_H && height == SMALL_PANEL_W)

    /**
     * Whether this car has a small cluster panel.
     *
     * [latchedSmallPanel] is a persisted observation, not a live reading, and that is the point:
     * once a panel has been forced to 1920x720 it reports 1920x720 from then on, so a live check
     * goes blind on exactly the cars already damaged. The configured type is honoured as well, for
     * an owner who set it before anything was ever observed.
     */
    @JvmStatic
    fun isSmallPanel(configuredType: Int, latchedSmallPanel: Boolean): Boolean =
        configuredType == CMD_8_8 || latchedSmallPanel

    /**
     * Whether [infoInt] may be sent. Non-shape commands are never the business of this policy and
     * always pass through untouched.
     */
    @JvmStatic
    fun allowShapeCommand(infoInt: Int, configuredType: Int, latchedSmallPanel: Boolean): Boolean {
        if (!isShapeCommand(infoInt)) return true
        if (infoInt == CMD_8_8) return true
        return !isSmallPanel(configuredType, latchedSmallPanel)
    }
}
