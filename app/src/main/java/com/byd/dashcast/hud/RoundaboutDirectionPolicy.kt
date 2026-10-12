package com.byd.dashcast.hud

import com.byd.dashcast.data.prefs.RoundaboutTrafficSide

/** Applies the user's circulation choice only after source, freshness and destination gates. */
object RoundaboutDirectionPolicy {
    fun apply(data: HudNavigationData, side: RoundaboutTrafficSide): HudNavigationData {
        if (side == RoundaboutTrafficSide.SOURCE) return data
        val clockwise = side == RoundaboutTrafficSide.LEFT
        if (data.genericRoundaboutDirection != null) {
            return HudNavigationData(-1, data.distanceMeters, data.roadName,
                data.remainingDistanceMeters, data.remainingTimeSeconds, data.etaHour, data.etaMinute,
                genericRoundaboutDirection = if (clockwise) GenericRoundaboutDirection.CLOCKWISE
                    else GenericRoundaboutDirection.COUNTERCLOCKWISE)
        }
        val id = data.iconId
        val icon = when (id) {
            in 25..34 -> if (clockwise) id + 10 else id
            in 35..44 -> if (clockwise) id else id - 10
            // These audited pairs retain left/right/straight/U-turn geometry. Their legacy
            // LEFT/RIGHT constant suffixes do not describe clockwise circulation reliably.
            15, 16 -> if (clockwise) 16 else 15
            17, 18 -> if (clockwise) 17 else 18
            19, 20 -> if (clockwise) 19 else 20
            21, 22 -> if (clockwise) 21 else 22
            // 23/24 have no confirmed circulation pair and are not produced by current parsers.
            else -> return data
        }
        // Native/AMap direction is explicit because their namespace differs from the CAN ids.
        // The ordinal remains encoded in numbered ids; generic pairs never invent an exit.
        return HudNavigationData(icon, data.distanceMeters, data.roadName,
            data.remainingDistanceMeters, data.remainingTimeSeconds, data.etaHour, data.etaMinute,
            clockwise)
    }
}
