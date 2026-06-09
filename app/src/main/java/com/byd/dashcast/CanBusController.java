package com.byd.dashcast;

import com.byd.dashcast.proxy.ProxyClient;
import com.byd.dashcast.proxy.daemon.CanWriteVerbs;

import java.nio.charset.StandardCharsets;

/**
 * CanBusController — high-level API for writing to the BYD instrument cluster HUD via CAN bus.
 *
 * <p>All methods route through the proxy daemon (uid 2000) using the CAN write verbs introduced
 * in Phase CAN-1 (protocol v10) and the SettingDevice verb added in v11. The daemon executes
 * the actual BYD SDK calls with a permission-bypass system context, which is required for write
 * operations even on a platform-signed process.
 *
 * <p>Typical usage flow:
 * <pre>
 *   // Start navigation display on the cluster (call once on nav session start):
 *   CanBusController.setNaviActive(true);
 *
 *   // Push a guidance update on each turn notification:
 *   CanBusController.sendSimpleGuidance(ICON_TURN_RIGHT, 300);
 *   CanBusController.sendNextStreetName("Rue de la Paix");
 *   CanBusController.sendRestRoute(0, 12, 3200);  // 12 min, 3.2 km
 *
 *   // Stop navigation display when the route ends:
 *   CanBusController.setNaviActive(false);
 * </pre>
 *
 * <p>All methods throw {@link ProxyClient.ProxyException} if the daemon is unreachable.
 *
 * @see com.byd.dashcast.hud.HudController — orchestration layer with deduplication
 * @see CanWriteVerbs — raw CAN feature ID constants (from OpenBYD 2.2 RE)
 * @since v1.4.7-beta (Phase CAN-1)
 */
public final class CanBusController {

    private CanBusController() {}

    // ─── Turn icon constants (from OpenBYD 2.2 HudController RE, 49 values) ─

    public static final int ICON_TURN_LEFT              = 1;
    public static final int ICON_TURN_RIGHT             = 2;
    public static final int ICON_SLIGHT_LEFT            = 3;
    public static final int ICON_SLIGHT_LEFT_ALT        = 4;
    public static final int ICON_SLIGHT_RIGHT           = 5;
    public static final int ICON_SLIGHT_RIGHT_ALT       = 6;
    public static final int ICON_SHARP_LEFT             = 7;
    public static final int ICON_SHARP_RIGHT            = 8;
    public static final int ICON_U_TURN_LEFT            = 9;
    public static final int ICON_U_TURN_RIGHT           = 10;
    public static final int ICON_STRAIGHT_SOLID         = 11;
    public static final int ICON_STRAIGHT_DOTTED        = 12;
    public static final int ICON_DETOUR_RIGHT           = 13;
    public static final int ICON_DETOUR_LEFT            = 14;
    // Roundabout entry-direction variants (OpenBYD names: ROUNDABOUT_3_4_*, etc.)
    public static final int ICON_ROUNDABOUT_3_4_LEFT     = 15;
    public static final int ICON_ROUNDABOUT_1_4_LEFT     = 16;
    public static final int ICON_ROUNDABOUT_3_4_RIGHT    = 17;
    public static final int ICON_ROUNDABOUT_1_4_RIGHT    = 18;
    public static final int ICON_ROUNDABOUT_STRAIGHT_L   = 19;
    public static final int ICON_ROUNDABOUT_STRAIGHT_R   = 20;
    public static final int ICON_ROUNDABOUT_L_TO_R       = 21;
    public static final int ICON_ROUNDABOUT_R_TO_L       = 22;
    public static final int ICON_ROUNDABOUT_STRAIGHT_A1  = 23;
    public static final int ICON_ROUNDABOUT_STRAIGHT_A2  = 24;
    // Roundabouts counter-clockwise (exit count 1-10)
    public static final int ICON_ROUNDABOUT_CCW_1_LAP   = 25;
    public static final int ICON_ROUNDABOUT_CCW_2_LAPS  = 26;
    public static final int ICON_ROUNDABOUT_CCW_3_LAPS  = 27;
    public static final int ICON_ROUNDABOUT_CCW_4_LAPS  = 28;
    public static final int ICON_ROUNDABOUT_CCW_5_LAPS  = 29;
    public static final int ICON_ROUNDABOUT_CCW_6_LAPS  = 30;
    public static final int ICON_ROUNDABOUT_CCW_7_LAPS  = 31;
    public static final int ICON_ROUNDABOUT_CCW_8_LAPS  = 32;
    public static final int ICON_ROUNDABOUT_CCW_9_LAPS  = 33;
    public static final int ICON_ROUNDABOUT_CCW_10_LAPS = 34;
    // Roundabouts clockwise (exit count 1-10)
    public static final int ICON_ROUNDABOUT_CW_1_LAP    = 35;
    public static final int ICON_ROUNDABOUT_CW_2_LAPS   = 36;
    public static final int ICON_ROUNDABOUT_CW_3_LAPS   = 37;
    public static final int ICON_ROUNDABOUT_CW_4_LAPS   = 38;
    public static final int ICON_ROUNDABOUT_CW_5_LAPS   = 39;
    public static final int ICON_ROUNDABOUT_CW_6_LAPS   = 40;
    public static final int ICON_ROUNDABOUT_CW_7_LAPS   = 41;
    public static final int ICON_ROUNDABOUT_CW_8_LAPS   = 42;
    public static final int ICON_ROUNDABOUT_CW_9_LAPS   = 43;
    public static final int ICON_ROUNDABOUT_CW_10_LAPS  = 44;
    // Special manoeuvres
    public static final int ICON_STOP_LEFT              = 45;
    public static final int ICON_PARKING_CAFE           = 46;
    public static final int ICON_TOLLBOOTH              = 47;
    public static final int ICON_DESTINATION            = 48;
    public static final int ICON_TUNNEL                 = 49;

    // ─── Navigation lifecycle ─────────────────────────────────────────────

    /**
     * Start or stop the navigation display on the instrument cluster HUD.
     *
     * <p>ON sequence (active=true):
     * <ol>
     *   <li>Set {@code INSTRUMENT_SEND_NAVI_STATUS} = 2 (active) via InstrumentDevice.</li>
     *   <li>Set {@code SETTING_NAVI_SCREEN_STATUS} = 3 via SettingDevice to activate the
     *       cluster navigation lane. Both writes are required — matching OpenBYD 2.2
     *       {@code sendAutoNaviStatus(2)} behaviour.</li>
     * </ol>
     *
     * <p>OFF sequence (active=false):
     * <ol>
     *   <li>Set {@code INSTRUMENT_SEND_NAVI_STATUS} = 4 (stopped).</li>
     *   <li>Clear all 8 HUD registers to their "no data" sentinel values so the
     *       cluster display resets cleanly to the default ADAS / speed view.</li>
     * </ol>
     */
    public static void setNaviActive(boolean active) throws ProxyClient.ProxyException {
        if (active) {
            ProxyClient.canNaviStatus(CanWriteVerbs.NAVI_STATUS_ACTIVE);
            ProxyClient.canSettingInt(CanWriteVerbs.SETTING_NAVI_SCREEN_STATUS, 3);
        } else {
            ProxyClient.canNaviStatus(CanWriteVerbs.NAVI_STATUS_STOPPED);
            // Clear all HUD registers — matching OpenBYD sendAutoNaviStatus(4) clear sequence.
            ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_GUIDE_SIMPLE, 0);
            ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_GUIDE_ROAD_DISTANCE, 0);
            ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_FRONT_CROSSING_DIST, -1);
            ProxyClient.canInstrumentBytes(CanWriteVerbs.INSTRUMENT_NEXT_PATHNAME, new byte[0]);
            ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_MILEAGE, -1);
            ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_HOUR, 0);
            ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_MINUTE, 0);
            ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_REMAINING_SEC, 0);
        }
    }

    // ─── Primary guidance ─────────────────────────────────────────────────

    /**
     * Send a simple guidance update: turn icon + distance to that turn.
     *
     * <p>Writes three CAN registers matching OpenBYD {@code sendSimpleGuidanceInfo}:
     * <ul>
     *   <li>{@code INSTRUMENT_GUIDE_SIMPLE} = iconId</li>
     *   <li>{@code INSTRUMENT_GUIDE_ROAD_DISTANCE} = iconId (same value — dual-display register)</li>
     *   <li>{@code INSTRUMENT_FRONT_CROSSING_DIST} = distanceMeters</li>
     * </ul>
     *
     * @param turnIconId     BYD turn icon ID — use {@code ICON_*} constants above
     * @param distanceMeters distance to the upcoming turn in metres
     */
    public static void sendSimpleGuidance(int turnIconId, int distanceMeters)
            throws ProxyClient.ProxyException {
        ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_GUIDE_SIMPLE, turnIconId);
        ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_GUIDE_ROAD_DISTANCE, turnIconId);
        ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_FRONT_CROSSING_DIST, distanceMeters);
    }

    /**
     * Send the secondary (advanced) guidance icon and distance.
     *
     * <p>Pass {@code -1} for both parameters to clear the secondary guidance
     * (required by OpenBYD when a step has no secondary info). Writes
     * {@code INSTRUMENT_NAVI_LEAD_MSG} and {@code INSTRUMENT_DISTANCE_TARGET_AHEAD}.
     *
     * @param iconId   secondary turn icon ID, or -1 to clear
     * @param distance distance to secondary event in metres, or -1 to clear
     */
    public static void sendSecondaryGuidance(int iconId, int distance)
            throws ProxyClient.ProxyException {
        ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_LEAD_MSG, iconId);
        ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_DISTANCE_TARGET_AHEAD, distance);
    }

    /**
     * Send the name of the next street / road segment as UTF-8.
     *
     * <p>Null or empty string clears the street name field on the cluster display.
     *
     * @param streetName next road name, or {@code null} / empty to clear
     */
    public static void sendNextStreetName(String streetName) throws ProxyClient.ProxyException {
        byte[] bytes = (streetName == null || streetName.isEmpty())
                ? new byte[0]
                : streetName.getBytes(StandardCharsets.UTF_8);
        ProxyClient.canInstrumentBytes(CanWriteVerbs.INSTRUMENT_NEXT_PATHNAME, bytes);
    }

    // ─── Remaining route info ─────────────────────────────────────────────

    /**
     * Update the remaining-route summary shown on the cluster (ETA + distance).
     *
     * <p>Mirrors OpenBYD {@code sendRestRouteInfo(restHour, restMinute, restMileage)}.
     * Writes four registers: MILEAGE, HOUR, MINUTE, REMAINING_SEC=0.
     *
     * <p>Callers are responsible for clamping: hours ∈ [0,254], minutes ∈ [0,59],
     * mileage ∈ [0, 0xFFFFFFFE]. {@link com.byd.dashcast.hud.HudController} does
     * this automatically from raw seconds + metres.
     *
     * @param restHour     ETA hours component (0–254)
     * @param restMinute   ETA minutes component (0–59)
     * @param restMileage  remaining distance in metres (cast to int internally)
     */
    public static void sendRestRoute(int restHour, int restMinute, long restMileage)
            throws ProxyClient.ProxyException {
        ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_MILEAGE, (int) restMileage);
        ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_HOUR, restHour);
        ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_MINUTE, restMinute);
        ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_REMAINING_SEC, 0);
    }

    // ─── Raw / advanced access ────────────────────────────────────────────

    /**
     * Write any integer value to any CAN instrument feature ID.
     *
     * @return SDK result code (0 = success).
     */
    public static int setFeatureInt(int featureId, int value) throws ProxyClient.ProxyException {
        return ProxyClient.canInstrumentInt(featureId, value);
    }

    /**
     * Write a byte buffer to any CAN instrument feature ID.
     *
     * @return SDK result code (0 = success).
     */
    public static int setFeatureBytes(int featureId, byte[] data) throws ProxyClient.ProxyException {
        return ProxyClient.canInstrumentBytes(featureId, data);
    }

    /**
     * Write any integer value to any CAN setting feature ID (BYDAutoSettingDevice).
     *
     * @return SDK result code (0 = success).
     */
    public static int setSettingFeature(int featureId, int value) throws ProxyClient.ProxyException {
        return ProxyClient.canSettingInt(featureId, value);
    }
}
