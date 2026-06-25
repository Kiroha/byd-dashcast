package com.byd.dashcast.hud

import android.content.Context
import android.content.Intent

/**
 * Sends the standard AutoNavi/AMap guidance broadcast
 * (`AUTONAVI_STANDARD_BROADCAST_SEND`) that the BYD system nav service consumes to
 * render turn-by-turn on the THT **and the instrument cluster**.
 *
 * This is the strongest candidate for the DL3 HUD: DiLink 3.0's built-in nav is
 * AMap/AutoNavi-based, and Open-BYD sends this broadcast IN ADDITION to the CAN path
 * "to natively update both THT and HUD displays". Even Open-BYD's CAN path does NOT
 * work on DL3 — so on DL3 this broadcast may be the only mechanism that renders.
 *
 * Extras mirror Open-BYD's `HudController.sendStandardAmapBroadcast`.
 */
object HudAutoNaviBroadcast {

    const val ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"

    /** AMap maneuver icon ids (subset; 4 = turn right, used by the self-test). */
    const val AMAP_ICON_RIGHT = 4

    fun sendGuide(
        ctx: Context,
        amapIcon: Int,
        distanceMeters: Int,
        roadName: String,
        remDistMeters: Int,
        remTimeSec: Int,
    ): String {
        val intent = Intent(ACTION).apply {
            putExtra("KEY_TYPE", 10001)          // KEY_TYPE_GUIDE_INFO
            putExtra("TYPE", 8)                  // 8 = GPS nav active
            putExtra("EXTRA_STATE", 8)           // 8 = navigating
            putExtra("EXTRA_IS_FOREGROUND", 0)
            putExtra("IS_BYD_MAP", true)
            putExtra("IS_BYD_BAIDU_MAP", false)
            putExtra("NEW_ICON", amapIcon)
            putExtra("SEG_REMAIN_DIS", distanceMeters)
            putExtra("NEXT_ROAD_NAME", roadName)
            putExtra("ROUTE_REMAIN_DIS", remDistMeters)
            putExtra("ROUTE_REMAIN_TIME", remTimeSec)
            putExtra("SEG_REMAIN_DIS_AUTO", "$distanceMeters m")
            putExtra("ROUTE_REMAIN_DIS_AUTO", "$remDistMeters m")
            val min = remTimeSec / 60
            putExtra("ROUTE_REMAIN_TIME_AUTO", "$min min")
            putExtra("ROUTE_REMAIN_TIME_STRING", "$min min")
        }
        ctx.sendBroadcast(intent)
        return "sent $ACTION (NEW_ICON=$amapIcon, SEG_REMAIN_DIS=$distanceMeters, NEXT_ROAD_NAME=\"$roadName\")"
    }

    fun sendStop(ctx: Context): String {
        val intent = Intent(ACTION).apply {
            putExtra("KEY_TYPE", 10001)
            putExtra("TYPE", 9)                  // 9 = none / stop
            putExtra("EXTRA_STATE", 1)
            putExtra("EXTRA_IS_FOREGROUND", 1)
            putExtra("IS_BYD_MAP", true)
            putExtra("IS_BYD_BAIDU_MAP", false)
            putExtra("NEW_ICON", -1)
            putExtra("SEG_REMAIN_DIS", -1)
            putExtra("NEXT_ROAD_NAME", "")
            putExtra("ROUTE_REMAIN_DIS", -1)
            putExtra("ROUTE_REMAIN_TIME", -1)
        }
        ctx.sendBroadcast(intent)
        return "sent $ACTION stop"
    }
}
