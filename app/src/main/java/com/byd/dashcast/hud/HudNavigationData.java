package com.byd.dashcast.hud;

/**
 * HudNavigationData — immutable snapshot of one navigation step.
 *
 * <p>Populated by {@link MapNotificationListenerService} from a Google Maps (or compatible
 * navigation app) notification and consumed by {@link HudController} to write the
 * appropriate values to the BYD instrument cluster HUD via {@code CanBusController}.
 *
 * <p>Turn icon IDs ({@link #iconId}) are BYD-internal constants; see the
 * {@code ICON_*} constants in {@link com.byd.dashcast.system.CanBusController} for the full list
 * of 49 values from the OpenBYD 2.2 reverse-engineering.
 */
public final class HudNavigationData {

    /** BYD turn icon ID (1–49). See {@code CanBusController.ICON_*} constants. */
    public final int iconId;

    /** Distance to the next turn in metres. Negative = invalid / should not display. */
    public final int distanceMeters;

    /** Name of the road to turn onto (UTF-8). Empty string if unknown. */
    public final String roadName;

    /** Total remaining route distance in metres, or {@code null} if unknown. */
    public final Integer remainingDistanceMeters;

    /** Total remaining route time in seconds, or {@code null} if unknown. */
    public final Integer remainingTimeSeconds;

    public HudNavigationData(int iconId,
                              int distanceMeters,
                              String roadName,
                              Integer remainingDistanceMeters,
                              Integer remainingTimeSeconds) {
        this.iconId = iconId;
        this.distanceMeters = distanceMeters;
        this.roadName = roadName != null ? roadName : "";
        this.remainingDistanceMeters = remainingDistanceMeters;
        this.remainingTimeSeconds = remainingTimeSeconds;
    }
}
