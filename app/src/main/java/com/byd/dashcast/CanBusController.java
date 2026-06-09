package com.byd.dashcast;

import com.byd.dashcast.proxy.ProxyClient;
import com.byd.dashcast.proxy.daemon.CanWriteVerbs;

import java.nio.charset.StandardCharsets;

/**
 * CanBusController — high-level API for writing to the BYD instrument cluster HUD via CAN bus.
 *
 * <p>All methods route through the proxy daemon (uid 2000) using the CAN write verbs introduced
 * in Phase CAN-1 (protocol version 10). The daemon executes the actual BYD SDK calls with a
 * permission-bypass system context, which is required for write operations even on a
 * platform-signed process.
 *
 * <p>Usage pattern:
 * <pre>
 *   // Ensure the daemon is connected first (typically done at app start):
 *   if (!ProxyClient.isConnected()) ProxyClient.connect(context);
 *
 *   // Start navigation display on the cluster:
 *   CanBusController.setNaviActive(true);
 *
 *   // Push a guidance update (call on each turn notification):
 *   CanBusController.sendSimpleGuidance(turnIconId, distanceMeters);
 *   CanBusController.sendNextStreetName("Rue de la Paix");
 *
 *   // Update remaining route:
 *   CanBusController.sendRestRoute(totalRemainingMeters, remainingMinutes);
 *
 *   // Stop navigation display when the route ends:
 *   CanBusController.setNaviActive(false);
 * </pre>
 *
 * <p>All methods throw {@link ProxyClient.ProxyException} if the daemon is unreachable.
 * Callers should handle this gracefully — the HUD simply shows nothing when writes fail.
 *
 * <p>For raw access to any CAN feature ID not covered by the named helpers, use
 * {@link #setFeatureInt} / {@link #setFeatureBytes} with constants from {@link CanWriteVerbs}.
 *
 * @see CanWriteVerbs — raw CAN feature ID constants (from OpenBYD 2.2 RE)
 * @see ProxyClient — daemon transport layer
 * @since v1.4.7-beta (Phase CAN-1)
 */
public final class CanBusController {

    private CanBusController() {}

    // ─── Navigation lifecycle ─────────────────────────────────────────────

    /**
     * Start or stop the navigation display on the instrument cluster HUD.
     *
     * <p>Call with {@code true} when a navigation session begins and with {@code false}
     * when it ends (route reached, cancelled, or app closed). The cluster resets its HUD
     * lane to the default speed / ADAS view when stopped.
     *
     * @return SDK result code (0 = success, non-zero = SDK error code).
     */
    public static int setNaviActive(boolean active) throws ProxyClient.ProxyException {
        return ProxyClient.canNaviStatus(
                active ? CanWriteVerbs.NAVI_STATUS_ACTIVE : CanWriteVerbs.NAVI_STATUS_STOPPED);
    }

    // ─── Guidance updates ─────────────────────────────────────────────────

    /**
     * Send a simple guidance update: the turn icon to display and the distance to that turn.
     *
     * <p>Call this whenever Google Maps (or any navigation source) emits a new turn instruction.
     * The icon IDs are BYD-internal; common values observed in OpenBYD:
     * <ul>
     *   <li>0 — no manoeuvre / straight ahead</li>
     *   <li>1 — turn right</li>
     *   <li>2 — turn left</li>
     *   <li>3 — slight right</li>
     *   <li>4 — slight left</li>
     *   <li>... (OEM-specific, check your cluster documentation)</li>
     * </ul>
     *
     * @param turnIconId     BYD turn-icon ID
     * @param distanceMeters distance to the upcoming turn in metres
     */
    public static void sendSimpleGuidance(int turnIconId, int distanceMeters)
            throws ProxyClient.ProxyException {
        ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_GUIDE_SIMPLE, turnIconId);
        ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_FRONT_CROSSING_DIST, distanceMeters);
    }

    /**
     * Send the name of the next street / road segment as a UTF-8 string.
     *
     * <p>The cluster typically shows this below the turn arrow. Null or empty string clears
     * the street name field.
     *
     * @param streetName next road name, or {@code null} to clear
     */
    public static void sendNextStreetName(String streetName) throws ProxyClient.ProxyException {
        byte[] bytes = (streetName == null || streetName.isEmpty())
                ? new byte[0]
                : streetName.getBytes(StandardCharsets.UTF_8);
        ProxyClient.canInstrumentBytes(CanWriteVerbs.INSTRUMENT_NEXT_PATHNAME, bytes);
    }

    // ─── Remaining route info ─────────────────────────────────────────────

    /**
     * Update the remaining-route summary shown on the cluster (distance + ETA).
     *
     * <p>Call periodically during navigation (e.g. every 10 s or on significant route change).
     *
     * @param remainingMeters  total remaining route distance in metres
     * @param remainingMinutes total remaining time in minutes
     */
    public static void sendRestRoute(int remainingMeters, int remainingMinutes)
            throws ProxyClient.ProxyException {
        ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_MILEAGE, remainingMeters);
        ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_MINUTE, remainingMinutes % 60);
        ProxyClient.canInstrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_HOUR,   remainingMinutes / 60);
    }

    // ─── Raw / advanced access ────────────────────────────────────────────

    /**
     * Write any integer value to any CAN instrument feature ID.
     *
     * <p>Use this for features not yet covered by the named helpers above.
     * Feature ID constants live in {@link CanWriteVerbs}.
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
}
