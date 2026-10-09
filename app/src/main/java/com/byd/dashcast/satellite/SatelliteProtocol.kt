package com.byd.dashcast.satellite

import com.byd.dashcast.hud.HudNavigationData
import com.byd.dashcast.system.CanBusController
import org.json.JSONObject
import java.net.InetAddress

/** Versioned, portable wire format. OEM icon numbers never cross the network boundary. */
object SatelliteProtocol {
    const val VERSION = 1
    const val PATH = "/satellite/v1"
    const val MAX_MESSAGE_BYTES = 65_536
    const val MAX_AGE_MS = 1_500L
    const val NAV_TIMEOUT_MS = 6_000L
    const val AUTH_TIMEOUT_MS = 5_000L

    data class Navigation(val sequence: Long, val ageMs: Long, val data: HudNavigationData?)

    fun decode(text: String): JSONObject {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES)
        return JSONObject(text).also { require(it.get("type") is String) }
    }

    fun navigation(json: JSONObject): Navigation {
        val seq = integer(json, "seq", 0, Long.MAX_VALUE)
        val age = integer(json, "ageMs", 0, MAX_AGE_MS)
        if (json.getString("type") == "navigation.stop") return Navigation(seq, age, null)
        require(json.getString("type") == "navigation.update")
        val maneuver = json.getString("maneuver")
        val icon = when (maneuver) {
            "left" -> CanBusController.ICON_TURN_LEFT
            "right" -> CanBusController.ICON_TURN_RIGHT
            "slight_left" -> CanBusController.ICON_SLIGHT_LEFT
            "slight_right" -> CanBusController.ICON_SLIGHT_RIGHT
            "sharp_left" -> CanBusController.ICON_SHARP_LEFT
            "sharp_right" -> CanBusController.ICON_SHARP_RIGHT
            "uturn_left" -> CanBusController.ICON_U_TURN_LEFT
            "uturn_right" -> CanBusController.ICON_U_TURN_RIGHT
            "straight" -> CanBusController.ICON_STRAIGHT_SOLID
            "destination" -> CanBusController.ICON_DESTINATION
            "roundabout_cw", "roundabout_ccw" -> {
                val exit = integer(json, "exit", 1, 10).toInt()
                (if (maneuver == "roundabout_cw") CanBusController.ICON_ROUNDABOUT_CW_1_LAP
                else CanBusController.ICON_ROUNDABOUT_CCW_1_LAP) + exit - 1
            }
            else -> throw IllegalArgumentException("unsupported maneuver")
        }
        val road = if (json.has("roadName")) json.get("roadName") else ""
        require(road is String && road.length <= 160 && road.none { it.isISOControl() })
        val hour = optionalInteger(json, "etaHour", 0, 23)
        val minute = optionalInteger(json, "etaMinute", 0, 59)
        require((hour == null) == (minute == null))
        return Navigation(seq, age, HudNavigationData(icon,
            integer(json, "distanceMeters", 0, 1_000_000).toInt(), road,
            optionalInteger(json, "remainingDistanceMeters", 0, 10_000_000),
            optionalInteger(json, "remainingTimeSeconds", 0, 604_800), hour, minute))
    }

    private fun optionalInteger(json: JSONObject, key: String, min: Long, max: Long): Int? =
        if (!json.has(key) || json.isNull(key)) null else integer(json, key, min, max).toInt()

    internal fun integer(json: JSONObject, key: String, min: Long, max: Long): Long {
        // JSONObject.getLong coerces strings and rounds fractions; reject both at the boundary.
        val raw = json.get(key)
        require(raw is Int || raw is Long)
        return (raw as Number).toLong().also { require(it in min..max) }
    }

    fun isLocalAddress(address: InetAddress): Boolean =
        !address.isAnyLocalAddress && !address.isMulticastAddress &&
            (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
                (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc))
}

/** Per-connection replay/freshness gate; invalid input cannot consume the next valid sequence. */
class SatelliteNavigationGate {
    private var lastSequence = -1L
    private var receivedAt: Long? = null
    private var sourceAgeMs = 0L

    @Synchronized
    fun accept(frame: SatelliteProtocol.Navigation, nowMs: Long): Boolean {
        if (frame.sequence <= lastSequence || frame.ageMs !in 0..SatelliteProtocol.MAX_AGE_MS) return false
        lastSequence = frame.sequence
        receivedAt = if (frame.data == null) null else nowMs
        sourceAgeMs = frame.ageMs
        return true
    }

    @Synchronized
    fun expire(nowMs: Long): Boolean {
        val received = receivedAt ?: return false
        if (nowMs - received + sourceAgeMs < SatelliteProtocol.NAV_TIMEOUT_MS) return false
        receivedAt = null
        return true
    }
}
