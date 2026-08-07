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
    private const val NAVI_STATE_STOPPED = 0

    /** Set once the container has been switched into nav mode for the current session. */
    @Volatile private var enabled = false

    /**
     * Maps our internal BYD turn-icon id ({@code CanBusController.ICON_*}, the CAN namespace) to the
     * **AMap `NEW_ICON` id** the cluster expects in `NaviInfo.nextTurnIcon`.
     *
     * These two namespaces are NOT the same, and the cluster's glyphs were decoded from a 29-photo
     * on-car icon sweep (ids 0..28, every id renders something). The three that matter were verified
     * directly on the photos — a mirrored left/right here would put a wrong arrow in a moving car:
     *
     *  * AMap **2 = turn left**, **3 = turn right**, **9 = straight ahead**.
     *
     * Also decoded: 4/5 slight left/right · 6/7 sharp left/right · 8 U-turn (left) · 0 U-turn (right)
     * · 13 service area (P + cup) · 14 toll booth/gate · 15 destination (checkered flag) · 16 arch
     * (tunnel) · 11 roundabout. Roundabout *exit numbering* is not decoded, so every roundabout
     * maps to the generic entering-roundabout glyph rather than guessing an exit.
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
        CanBusController.ICON_U_TURN_LEFT      -> 8
        CanBusController.ICON_U_TURN_RIGHT     -> 0
        CanBusController.ICON_STRAIGHT_SOLID,
        CanBusController.ICON_STRAIGHT_DOTTED  -> 9
        CanBusController.ICON_PARKING_CAFE     -> 13   // "P" + coffee cup = service area
        CanBusController.ICON_TOLLBOOTH        -> 14   // booth / gate
        CanBusController.ICON_DESTINATION      -> 15   // checkered finish flag
        CanBusController.ICON_TUNNEL           -> 16   // arch / portal
        // Every roundabout variant (entry-direction 15-24, CCW 25-34, CW 35-44) → generic roundabout.
        in 15..44                              -> 11
        else                                   -> 9    // unknown → straight, never a turn
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
            ProxyClient.autoContainerSendInfo(TYPE_NAV_MODE, 0, "")
            enabled = true
            Log.i(TAG, "cluster nav-mode enabled (sendInfo(5,0))")
        } catch (t: Throwable) {
            Log.w(TAG, "cluster nav-mode enable failed: ${t.message}")
        }
    }

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
                routeRemainDist = d.remainingDistanceMeters ?: 0)
            ProxyClient.autoContainerSendInfo2(TYPE_NAVI_INFO, payload)
        } catch (t: Throwable) {
            Log.w(TAG, "cluster push failed: ${t.message}")
        }
    }

    /** Clears the cluster guidance at the end of a route. Best-effort; never throws. */
    @JvmStatic
    fun stop() {
        try {
            val payload = NaviInfoPayloadBuilder.build(
                naviState = NAVI_STATE_STOPPED,
                nextRouteName = "",
                curToSegmentDist = 0,
                nextTurnIcon = 0,
                routeRemainTime = 0,
                routeRemainDist = 0)
            ProxyClient.autoContainerSendInfo2(TYPE_NAVI_INFO, payload)
        } catch (t: Throwable) {
            Log.w(TAG, "cluster stop failed: ${t.message}")
        } finally {
            enabled = false   // next route re-sends the nav-mode enable
        }
    }
}
