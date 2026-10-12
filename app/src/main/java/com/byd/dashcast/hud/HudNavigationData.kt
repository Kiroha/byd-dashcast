package com.byd.dashcast.hud

/** Known circulation without a known exit or exit geometry. Never a physical CAN icon. */
enum class GenericRoundaboutDirection { CLOCKWISE, COUNTERCLOCKWISE }

/**
 * HudNavigationData — immutable snapshot of one navigation step.
 *
 * Populated by [MapNotificationListenerService] from a Google Maps (or compatible
 * navigation app) notification and consumed by [HudController] to write the
 * appropriate values to the BYD instrument cluster HUD via `CanBusController`.
 *
 * Turn icon IDs ([iconId]) are BYD-internal constants; see the
 * `ICON_*` constants in [com.byd.dashcast.system.CanBusController] for the full list
 * of 49 values from the OpenBYD 2.2 reverse-engineering.
 */
class HudNavigationData(
    iconId: Int,
    distanceMeters: Int,
    roadName: String?,
    remainingDistanceMeters: Int?,
    remainingTimeSeconds: Int?,
    etaHour: Int?,
    etaMinute: Int?,
    // Audited local image circulation or a render-only traffic choice; never a Satellite field.
    val roundaboutClockwiseOverride: Boolean? = null,
    // A portable generic roundabout has no numbered CAN equivalent. iconId stays absent (-1).
    val genericRoundaboutDirection: GenericRoundaboutDirection? = null
) {

    /** BYD turn icon ID (1–49), or -1 for an explicit generic roundabout semantic. */
    @JvmField
    val iconId: Int = iconId

    /** Distance to the next turn in metres. Negative = invalid / should not display. */
    @JvmField
    val distanceMeters: Int = distanceMeters

    /** Name of the road to turn onto (UTF-8). Empty string if unknown. */
    @JvmField
    val roadName: String = roadName ?: ""

    /** Total remaining route distance in metres, or `null` if unknown. */
    @JvmField
    val remainingDistanceMeters: Int? = remainingDistanceMeters

    /** Total remaining route time in seconds, or `null` if unknown. */
    @JvmField
    val remainingTimeSeconds: Int? = remainingTimeSeconds

    /** Arrival wall-clock hour (0-23), or `null` if the notification carried no ETA time. */
    @JvmField
    val etaHour: Int? = etaHour

    /** Arrival wall-clock minute (0-59), or `null` if the notification carried no ETA time. */
    @JvmField
    val etaMinute: Int? = etaMinute
}
