package com.byd.dashcast.hud

import android.util.Log

import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.system.CanBusController

/**
 * Pushes live turn-by-turn onto the DiLink instrument **CLUSTER** through the OEM's own
 * AutoContainer channel — `sendInfo(5,0,"")` to switch the 1for2 container into navigation mode,
 * then `sendInfo2(4, <NaviInfo FlatBuffer>)` per guidance frame. That is the exact call the factory
 * nav app makes (`AmapService.sendNaviInfoTo1for2Clster`), issued here through the uid-2000 daemon.
 *
 * This is the **second output path**, complementary to the CAN writes in [HudController]:
 *  * CAN  → the **windshield HUD** (video-confirmed).
 *  * here → the **instrument cluster** (on-car confirmed 2026-08-04/07: the owner's DL3 SX326, a
 *    second SX326, and a DiLink 5.0 SW155 all render arrows this way).
 *
 * Cars **without** a windshield HUD — the majority — get turn arrows on the cluster from this path
 * alone, which is why it ships alongside, not instead of, the CAN path.
 *
 * Every call is best-effort and fully guarded: the cluster is a bonus surface and must never break
 * the proven CAN path, the notification pipeline, or the daemon.
 */
object ClusterNavPusher {

    private const val TAG = "ClusterNavPusher"

    /** AutoContainer "info" type for nav CONTENT (the FlatBuffer payload). */
    private const val TYPE_NAVI_INFO = 4
    /** AutoContainer "info" type the OEM sends once to put the container into nav mode. */
    private const val TYPE_NAV_MODE = 5

    /** naviState in the NaviInfo FlatBuffer: 1 = guiding (what the OEM sends while navigating). */
    private const val NAVI_STATE_GUIDING = 1

    /**
     * naviState the OEM writes when it stops guiding.
     *
     * Was 0, which is a GUIDING state, not a stopped one — docs/HUD_DILINK3_HANDOFF.md:146 records
     * TYPE = 0 or 1 as the two guiding values. The OEM's own clear
     * (AmapService.reSetGuideInfo → sendNaviInfoTo1for2Clster) sets naviState = 9, and this app's
     * broadcast close path has always sent TYPE = 9 (HudController.sendAmapStopBroadcast); only the
     * sendInfo2 path disagreed.
     */
    private const val NAVI_STATE_STOPPED = 9

    /**
     * nextTurnIcon that actually clears the glyph.
     *
     * Icon id 0 is a NO-OP: the cluster's Navigation.qml keeps whatever arrow it was already
     * showing (firmware-confirmed from cluster_theme{1,2}.rcc, and the reason U_TURN_RIGHT had to
     * move off 0). So the end-of-route frame was leaving the last manoeuvre arrow lit on the
     * cluster. The OEM clears with -1 (AmapService:151), and its reader treats -1 as "no icon".
     * The FlatBuffer builder omits a scalar equal to its 0 default, so the old frame did not even
     * carry the field — -1 is written.
     */
    private const val NO_TURN_ICON = -1

    /** Set once the container has been switched into nav mode for the current session. */
    @Volatile private var enabled = false

    /** CCW (counter-clockwise, "left") roundabout with a known exit index, 1..10 → cluster round_left_N. */
    private val ROUNDABOUT_CCW =
        CanBusController.ICON_ROUNDABOUT_CCW_1_LAP..CanBusController.ICON_ROUNDABOUT_CCW_10_LAPS
    /** CW (clockwise, "right") roundabout with a known exit index, 1..10 → cluster round_right_N. */
    private val ROUNDABOUT_CW =
        CanBusController.ICON_ROUNDABOUT_CW_1_LAP..CanBusController.ICON_ROUNDABOUT_CW_10_LAPS

    /**
     * Maps our internal BYD turn-icon id ({@code CanBusController.ICON_*}, the CAN namespace) to the
     * cluster's `turnIconId` the OEM expects in `NaviInfo.nextTurnIcon` / the AMap `NEW_ICON` extra.
     *
     * The two namespaces are NOT the same. This table is now the firmware ground truth: decompiled
     * from the cluster's own Qt QML (`Navigation.qml` / `SimpleNavi.qml` `switch(turnIconId)`, ids
     * 0..28, byte-identical across cluster_theme1/2.rcc), superseding the earlier photo-sweep guesses.
     *
     *  * 2 = turn_left · 3 = turn_right · 9 = straight (go-straight maneuver) · 20 = direct (stay on road)
     *  * 4/5 = bear left/right · 6/7 = sharp/rear left/right · 8 = U-turn LEFT · **19 = U-turn RIGHT**
     *  * 13 service_area · 14 toll_station · 15 destination · 16 tunnel
     *  * 11 = enter_right_roundabout (CW) · 17 = enter_left_roundabout (CCW); when the exit index is
     *    known (1..10) the firmware renders round_{right,left}_N from the separate `roungAboutNum`
     *    field — see [roundaboutExitNum], emitted alongside in [push].
     *
     * Two firmware-verified corrections vs. the old table: **id 0 is a NO-OP** (Navigation.qml keeps
     * the previous glyph), so the old `U_TURN_RIGHT -> 0` left a right U-turn showing the stale icon;
     * it is now **19** (turn_right_about). And STRAIGHT_DOTTED is `direct` (20), not `straight` (9).
     */
    @JvmStatic
    fun toAmapIcon(bydIconId: Int): Int = when (bydIconId) {
        CanBusController.ICON_TURN_LEFT        -> 2
        CanBusController.ICON_TURN_RIGHT       -> 3
        CanBusController.ICON_SLIGHT_LEFT,
        CanBusController.ICON_SLIGHT_LEFT_ALT,
        CanBusController.ICON_DETOUR_LEFT      -> 4
        CanBusController.ICON_SLIGHT_RIGHT,
        CanBusController.ICON_SLIGHT_RIGHT_ALT,
        CanBusController.ICON_DETOUR_RIGHT     -> 5
        CanBusController.ICON_SHARP_LEFT       -> 6
        CanBusController.ICON_SHARP_RIGHT      -> 7
        CanBusController.ICON_U_TURN_LEFT      -> 8    // turn_left_about
        CanBusController.ICON_U_TURN_RIGHT     -> 19   // turn_right_about (was 0 = no-op → stale glyph)
        CanBusController.ICON_STRAIGHT_SOLID   -> 9    // straight (go-straight maneuver)
        CanBusController.ICON_STRAIGHT_DOTTED  -> 20   // direct (continue on current road)
        CanBusController.ICON_PARKING_CAFE     -> 13   // "P" + coffee cup = service area
        CanBusController.ICON_TOLLBOOTH        -> 14   // booth / gate
        CanBusController.ICON_DESTINATION      -> 15   // checkered finish flag
        CanBusController.ICON_TUNNEL           -> 16   // arch / portal
        // Roundabout direction: left/CCW → 17 (enter_left), right/CW → 11 (enter_right). Exit index
        // (round_{left,right}_N) goes through roungAboutNum, not the icon id.
        CanBusController.ICON_ROUNDABOUT_3_4_LEFT,
        CanBusController.ICON_ROUNDABOUT_1_4_LEFT,
        CanBusController.ICON_ROUNDABOUT_STRAIGHT_L -> 17
        in ROUNDABOUT_CCW                      -> 17
        in ROUNDABOUT_CW                       -> 11
        in 15..44                              -> 11   // remaining roundabout entry variants → generic right
        else                                   -> 9    // unknown → straight, never a turn
    }

    /**
     * The roundabout exit index (1..10) encoded in the BYD icon id by [MapNotificationListenerService]
     * (CCW 25-34 / CW 35-44), or 0 when the maneuver is not a numbered roundabout. Emitted into the
     * NaviInfo `roungAboutNum` field so the cluster renders `round_{left,right}_N`; 0 falls back to the
     * generic enter glyph (firmware only uses N when it is 1..10).
     */
    @JvmStatic
    fun roundaboutExitNum(bydIconId: Int): Int = when (bydIconId) {
        in ROUNDABOUT_CCW -> bydIconId - CanBusController.ICON_ROUNDABOUT_CCW_1_LAP + 1
        in ROUNDABOUT_CW  -> bydIconId - CanBusController.ICON_ROUNDABOUT_CW_1_LAP + 1
        else              -> 0
    }

    /**
     * Switches the container into navigation mode (idempotent for the session). The OEM sends this
     * once on the 1for2 branch before pushing content; without it the container can accept every
     * NaviInfo and render nothing — proven on-car, it is what turned a silent bench into arrows.
     */
    @JvmStatic
    fun enable() {
        if (enabled) return
        try {
            val result = ProxyClient.autoContainerSendInfoResultCompatible(TYPE_NAV_MODE, 0, "")
            if (!activationAccepted(result)) {
                Log.w(TAG, "cluster nav-mode rejected (sendInfo(5,0) rc=$result)")
                return
            }
            enabled = true
            Log.i(TAG, "cluster nav-mode enabled (sendInfo(5,0))")
        } catch (t: Throwable) {
            Log.w(TAG, "cluster nav-mode enable failed: ${t.message}")
        }
    }

    internal fun activationAccepted(nativeResult: Int?): Boolean =
        nativeResult == null || nativeResult == 0

    /** Pushes one guidance frame onto the cluster. Best-effort; never throws. */
    @JvmStatic
    fun push(d: HudNavigationData) {
        // Self-heal: the caller enables nav mode when the CAN path activates, but the cluster must not
        // depend on CAN succeeding — on a car with no windshield HUD this is the ONLY arrow surface,
        // and a container that never received sendInfo(5,0) accepts every frame and renders nothing.
        if (!enabled) enable()
        try {
            val payload = NaviInfoPayloadBuilder.build(
                naviState = NAVI_STATE_GUIDING,
                nextRouteName = d.roadName,
                curToSegmentDist = d.distanceMeters,
                nextTurnIcon = toAmapIcon(d.iconId),
                routeRemainTime = d.remainingTimeSeconds ?: 0,
                routeRemainDist = d.remainingDistanceMeters ?: 0,
                roungAboutNum = roundaboutExitNum(d.iconId))
            ProxyClient.autoContainerSendInfo2(TYPE_NAVI_INFO, payload)
        } catch (t: Throwable) {
            Log.w(TAG, "cluster push failed: ${t.message}")
        }
    }

    /**
     * The end-of-route frame, built apart from [stop] so a test can pin it.
     *
     * It is the only frame whose entire job is to make something disappear, and every value that
     * means "no change" is indistinguishable from a working clear until an owner reports an arrow
     * stuck on their cluster. Mirrors the OEM's reSetGuideInfo: -1 everywhere, because 0 is a
     * legitimate distance and reads as "0 m to the turn", not as "there is no turn".
     */
    internal fun buildClearPayload(): ByteArray = NaviInfoPayloadBuilder.build(
        naviState = NAVI_STATE_STOPPED,
        nextRouteName = "",
        curToSegmentDist = -1,
        nextTurnIcon = NO_TURN_ICON,
        routeRemainTime = -1,
        routeRemainDist = -1)

    /** Clears the cluster guidance at the end of a route. Best-effort; never throws. */
    @JvmStatic
    fun stop() {
        try {
            ProxyClient.autoContainerSendInfo2(TYPE_NAVI_INFO, buildClearPayload())
        } catch (t: Throwable) {
            Log.w(TAG, "cluster stop failed: ${t.message}")
        } finally {
            enabled = false   // next route re-sends the nav-mode enable
        }
    }
}
