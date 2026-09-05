package com.byd.dashcast.system

import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.CanWriteVerbs

import java.nio.charset.Charset
import java.util.Locale

/**
 * CanBusController — high-level API for writing to the BYD instrument cluster HUD via CAN bus.
 *
 * All methods route through the proxy daemon (uid 2000) using the CAN write verbs introduced in
 * Phase CAN-1 (protocol v10) and the SettingDevice verb added in v11. The daemon executes the
 * actual BYD SDK calls with a permission-bypass system context, which is required for write
 * operations even on a platform-signed process.
 *
 * Typical usage flow:
 * ```
 *   // Start navigation display on the cluster (call once on nav session start):
 *   CanBusController.setNaviActive(true)
 *
 *   // Push a guidance update on each turn notification:
 *   CanBusController.sendSimpleGuidance(ICON_TURN_RIGHT, 300)
 *   CanBusController.sendNextStreetName("Rue de la Paix")
 *   CanBusController.sendRestRoute(0, 12, 3200)  // 12 min, 3.2 km
 *
 *   // Stop navigation display when the route ends:
 *   CanBusController.setNaviActive(false)
 * ```
 *
 * All methods throw [ProxyClient.ProxyException] if the daemon is unreachable.
 *
 * Kotlin port note: the 49 ICON_* values below are a contract with the CLUSTER FIRMWARE, not
 * internal identifiers — they are the glyph ids the MCU renders. They were transcribed
 * mechanically from the Java source rather than by hand, and the whole set is diffed against the
 * Batch-0 baseline (docs/migration/kotlin-baseline/wire-constants.txt) after every build.
 *
 * @see com.byd.dashcast.hud.HudController — orchestration layer with deduplication
 * @see CanWriteVerbs — raw CAN feature ID constants (from OpenBYD 2.2 RE)
 * @since v1.4.7-beta (Phase CAN-1)
 */
object CanBusController {

    private val LEGACY_WRITER = object : CanBatchOperation.Writer {
        override fun setNaviStatus(status: Int): Int = ProxyClient.canNaviStatus(status)
        override fun setInstrumentInt(featureId: Int, value: Int): Int =
                ProxyClient.canInstrumentInt(featureId, value)
        override fun setInstrumentBytes(featureId: Int, bytes: ByteArray): Int =
                ProxyClient.canInstrumentBytes(featureId, bytes)
        override fun setSettingInt(featureId: Int, value: Int): Int =
                ProxyClient.canSettingInt(featureId, value)
    }

    /** Executes one write group, using truthful batch semantics on protocol v24+. */
    @JvmStatic
    @Throws(ProxyClient.ProxyException::class)
    fun sendBatch(operations: List<CanBatchOperation>?) {
        if (operations == null || operations.isEmpty()) return
        if (operations.size > CanBatchOperation.MAX_BATCH_SIZE) {
            throw ProxyClient.ProxyException("CAN batch too large: " + operations.size)
        }
        if (ProxyClient.supportsProtocol(24)) {
            val applied = ProxyClient.canBatch(operations)
            if (applied != operations.size) {
                throw ProxyClient.ProxyException(
                        "CAN batch incomplete: " + applied + "/" + operations.size)
            }
            return
        }
        try {
            val applied = CanBatchOperation.executeAcceptedPrefix(operations, LEGACY_WRITER)
            if (applied != operations.size) {
                throw ProxyClient.ProxyException(
                        "legacy CAN batch incomplete: " + applied + "/" + operations.size)
            }
        } catch (proxyError: ProxyClient.ProxyException) {
            throw proxyError
        } catch (error: Throwable) {
            throw ProxyClient.ProxyException("legacy CAN batch failed", error)
        }
    }

    // ─── Turn icon constants (from OpenBYD 2.2 HudController RE, 49 values) ─

    const val ICON_TURN_LEFT                     = 1
    const val ICON_TURN_RIGHT                    = 2
    const val ICON_SLIGHT_LEFT                   = 3
    const val ICON_SLIGHT_LEFT_ALT               = 4
    const val ICON_SLIGHT_RIGHT                  = 5
    const val ICON_SLIGHT_RIGHT_ALT              = 6
    const val ICON_SHARP_LEFT                    = 7
    const val ICON_SHARP_RIGHT                   = 8
    const val ICON_U_TURN_LEFT                   = 9
    const val ICON_U_TURN_RIGHT                  = 10
    const val ICON_STRAIGHT_SOLID                = 11
    const val ICON_STRAIGHT_DOTTED               = 12
    const val ICON_DETOUR_RIGHT                  = 13
    const val ICON_DETOUR_LEFT                   = 14
    // Roundabout entry-direction variants (OpenBYD names: ROUNDABOUT_3_4_*, etc.)
    const val ICON_ROUNDABOUT_3_4_LEFT           = 15
    const val ICON_ROUNDABOUT_1_4_LEFT           = 16
    const val ICON_ROUNDABOUT_3_4_RIGHT          = 17
    const val ICON_ROUNDABOUT_1_4_RIGHT          = 18
    const val ICON_ROUNDABOUT_STRAIGHT_L         = 19
    const val ICON_ROUNDABOUT_STRAIGHT_R         = 20
    const val ICON_ROUNDABOUT_L_TO_R             = 21
    const val ICON_ROUNDABOUT_R_TO_L             = 22
    const val ICON_ROUNDABOUT_STRAIGHT_A1        = 23
    const val ICON_ROUNDABOUT_STRAIGHT_A2        = 24
    // Roundabouts counter-clockwise (exit count 1-10)
    const val ICON_ROUNDABOUT_CCW_1_LAP          = 25
    const val ICON_ROUNDABOUT_CCW_2_LAPS         = 26
    const val ICON_ROUNDABOUT_CCW_3_LAPS         = 27
    const val ICON_ROUNDABOUT_CCW_4_LAPS         = 28
    const val ICON_ROUNDABOUT_CCW_5_LAPS         = 29
    const val ICON_ROUNDABOUT_CCW_6_LAPS         = 30
    const val ICON_ROUNDABOUT_CCW_7_LAPS         = 31
    const val ICON_ROUNDABOUT_CCW_8_LAPS         = 32
    const val ICON_ROUNDABOUT_CCW_9_LAPS         = 33
    const val ICON_ROUNDABOUT_CCW_10_LAPS        = 34
    // Roundabouts clockwise (exit count 1-10)
    const val ICON_ROUNDABOUT_CW_1_LAP           = 35
    const val ICON_ROUNDABOUT_CW_2_LAPS          = 36
    const val ICON_ROUNDABOUT_CW_3_LAPS          = 37
    const val ICON_ROUNDABOUT_CW_4_LAPS          = 38
    const val ICON_ROUNDABOUT_CW_5_LAPS          = 39
    const val ICON_ROUNDABOUT_CW_6_LAPS          = 40
    const val ICON_ROUNDABOUT_CW_7_LAPS          = 41
    const val ICON_ROUNDABOUT_CW_8_LAPS          = 42
    const val ICON_ROUNDABOUT_CW_9_LAPS          = 43
    const val ICON_ROUNDABOUT_CW_10_LAPS         = 44
    // Special manoeuvres
    const val ICON_STOP_LEFT                     = 45
    const val ICON_PARKING_CAFE                  = 46
    const val ICON_TOLLBOOTH                     = 47
    const val ICON_DESTINATION                   = 48
    const val ICON_TUNNEL                        = 49

    // ─── Navigation lifecycle ─────────────────────────────────────────────

    /**
     * Start or stop the navigation display on the instrument cluster HUD.
     *
     * ON sequence (active=true):
     *  1. Set `INSTRUMENT_SEND_NAVI_STATUS` = 2 (active) via InstrumentDevice.
     *  2. Set `SETTING_NAVI_SCREEN_STATUS` = 3 via SettingDevice to activate the cluster
     *     navigation lane. Both writes are required — matching OpenBYD 2.2
     *     `sendAutoNaviStatus(2)` behaviour.
     *
     * OFF sequence (active=false):
     *  1. Set `INSTRUMENT_SEND_NAVI_STATUS` = 4 (stopped).
     *  2. Clear the remaining HUD registers to their "no data" sentinel values so the cluster
     *     display resets cleanly to the default ADAS / speed view.
     */
    @JvmStatic
    @Throws(ProxyClient.ProxyException::class)
    fun setNaviActive(active: Boolean) {
        sendBatch(CanNavigationBatches.navigationState(active))
    }

    /**
     * Re-assert the navigation status heartbeat (`INSTRUMENT_SEND_NAVI_STATUS` = active).
     *
     * OEM parity (`AmapService.sendNavigateInfoToCAN`): the factory nav writes this register on
     * every guidance frame; a cluster that reads it as a liveness signal hides the guidance
     * widget if it stops. [com.byd.dashcast.hud.HudController] calls this once per navigation
     * update so the widget survives long steps where the icon/distance don't change. Unlike
     * [setNaviActive] it writes ONLY the status register, not the nav-screen-status setting the
     * OEM writes only at nav-start.
     */
    @JvmStatic
    @Throws(ProxyClient.ProxyException::class)
    fun sendNaviStatusHeartbeat() {
        sendBatch(CanNavigationBatches.navStatusHeartbeat())
    }

    // ─── Primary guidance ─────────────────────────────────────────────────

    /**
     * Send a simple guidance update: turn icon + distance to that turn.
     *
     * Writes CAN registers matching OpenBYD `sendSimpleGuidanceInfo`:
     * `INSTRUMENT_GUIDE_SIMPLE` = iconId, `INSTRUMENT_FRONT_CROSSING_DIST` = distanceMeters.
     *
     * @param turnIconId     BYD turn icon ID — use the `ICON_*` constants above
     * @param distanceMeters distance to the upcoming turn in metres
     */
    @JvmStatic
    @Throws(ProxyClient.ProxyException::class)
    fun sendSimpleGuidance(turnIconId: Int, distanceMeters: Int) {
        sendBatch(CanNavigationBatches.simpleGuidance(turnIconId, distanceMeters))
    }

    /**
     * Send the secondary (advanced) guidance icon and distance.
     *
     * Pass `-1` for both parameters to clear the secondary guidance (required by OpenBYD when a
     * step has no secondary info). Writes `INSTRUMENT_NAVI_LEAD_MSG` and
     * `INSTRUMENT_DISTANCE_TARGET_AHEAD`.
     *
     * @param iconId   secondary turn icon ID, or -1 to clear
     * @param distance distance to secondary event in metres, or -1 to clear
     */
    @JvmStatic
    @Throws(ProxyClient.ProxyException::class)
    fun sendSecondaryGuidance(iconId: Int, distance: Int) {
        sendBatch(CanNavigationBatches.secondaryGuidance(iconId, distance))
    }

    /**
     * Send the name of the next street / road segment as UTF-16LE (no BOM).
     *
     * The encoding is NOT free: the OEM nav writes this same register with
     * `str.getBytes("UnicodeLittleUnmarked")` — UTF-16 little-endian without a BOM — so the MCU
     * decodes the buffer as UTF-16LE. DashCast sent UTF-8 here from the start, which the MCU
     * rendered as arbitrary CJK codepoints: a field report described "distance and some Chinese
     * text" on the HUD, which was this bug, visible on-glass, and was misfiled as a partial
     * failure. Verified against AmapService.java:489/623/628.
     *
     * Null or empty string clears the street name field on the cluster display.
     *
     * @param streetName next road name, or `null` / empty to clear
     */
    @JvmStatic
    @Throws(ProxyClient.ProxyException::class)
    fun sendNextStreetName(streetName: String?) {
        val bytes: ByteArray = if (streetName == null || streetName.isEmpty()) ByteArray(0)
                               else streetName.toByteArray(Charset.forName("UTF-16LE"))
        sendBatch(CanNavigationBatches.nextStreetName(bytes))
    }

    // ─── Remaining route info ─────────────────────────────────────────────

    /**
     * Update the remaining-route summary shown on the cluster (ETA + distance).
     *
     * Mirrors OpenBYD `sendRestRouteInfo(restHour, restMinute, restMileage)`. Writes four
     * registers: MILEAGE, HOUR, MINUTE, REMAINING_SEC=0.
     *
     * Callers are responsible for clamping: hours ∈ [0,254], minutes ∈ [0,59],
     * mileage ∈ [0, 0xFFFFFFFE]. [com.byd.dashcast.hud.HudController] does this automatically
     * from raw seconds + metres.
     *
     * @param restHour     ETA hours component (0–254)
     * @param restMinute   ETA minutes component (0–59)
     * @param restMileage  remaining distance in metres (cast to int internally)
     */
    @JvmStatic
    @Throws(ProxyClient.ProxyException::class)
    fun sendRestRoute(restHour: Int, restMinute: Int, restMileage: Long) {
        sendBatch(CanNavigationBatches.restRoute(restHour, restMinute, restMileage))
    }

    /**
     * Update the wall-clock ETA shown on the cluster ("arrive at HH:MM").
     *
     * OEM parity (`AmapService.sendNavigateInfoToCAN`, EXPECTED_ARRIVE_* family): this is the
     * ARRIVAL CLOCK, distinct from [sendRestRoute] which sends the remaining DURATION. Writes
     * DAY, HOUR, MINUTE and SECOND=0 (the second register latches the triple).
     *
     * @param day    arrival day-code (1=today); a Maps/Waze notification carries no day → pass 1
     * @param hour   arrival hour (0-23)
     * @param minute arrival minute (0-59)
     */
    @JvmStatic
    @Throws(ProxyClient.ProxyException::class)
    fun sendExpectedArrival(day: Int, hour: Int, minute: Int) {
        sendBatch(CanNavigationBatches.expectedArrival(day, hour, minute))
    }

    // ─── Raw / advanced access ────────────────────────────────────────────

    /**
     * Write any integer value to any CAN instrument feature ID.
     *
     * @return SDK result code (0 = success).
     */
    @JvmStatic
    @Throws(ProxyClient.ProxyException::class)
    fun setFeatureInt(featureId: Int, value: Int): Int =
            ProxyClient.canInstrumentInt(featureId, value)

    /**
     * Write a byte buffer to any CAN instrument feature ID.
     *
     * @return SDK result code (0 = success).
     */
    @JvmStatic
    @Throws(ProxyClient.ProxyException::class)
    fun setFeatureBytes(featureId: Int, data: ByteArray?): Int =
            ProxyClient.canInstrumentBytes(featureId, data)

    /**
     * Write any integer value to any CAN setting feature ID (BYDAutoSettingDevice).
     *
     * @return SDK result code (0 = success).
     */
    @JvmStatic
    @Throws(ProxyClient.ProxyException::class)
    fun setSettingFeature(featureId: Int, value: Int): Int =
            ProxyClient.canSettingInt(featureId, value)
}
