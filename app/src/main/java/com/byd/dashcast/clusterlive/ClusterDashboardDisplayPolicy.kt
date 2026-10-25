package com.byd.dashcast.clusterlive

import com.byd.dashcast.cluster.display.ClusterDisplayInfo
import com.byd.dashcast.cluster.display.ClusterDisplayNames
import org.json.JSONArray
import org.json.JSONObject

/** Admission for this diagnostic only; the normal display-selection policy is unchanged. */
internal object ClusterDashboardDisplayPolicy {
    data class Evidence(val verdict: String, val reason: String, val displays: List<ClusterDisplayInfo>) {
        val ready: Boolean get() = verdict == "READY"
        fun json(): JSONObject = JSONObject().put("verdict", verdict).put("reason", reason)
            .put("candidates", JSONArray(displays.map { d -> JSONObject().put("id", d.id).put("name", d.name)
                .put("width", d.width).put("height", d.height).put("state", d.state)
                .put("ownerUid", d.ownerUid).put("ownerPackage", d.ownerPackage)
                .put("private", d.isPrivate) }))
    }

    fun inspect(displays: List<ClusterDisplayInfo>, complete: Boolean = true): Evidence {
        if (!complete || displays.isEmpty()) return Evidence("UNAVAILABLE", "No complete DMS snapshot", emptyList())
        val candidates = displays.filter { it.id != 0 && ClusterDisplayNames.isKnownClusterName(it.name) }
        fun result(verdict: String, reason: String) = Evidence(verdict, reason, candidates)
        if (candidates.isEmpty()) return result("ABSENT", "No OEM projection display exists yet")
        if (candidates.any { it.ownerUid < 0 || it.ownerPackage.isNullOrEmpty() })
            return result("OWNER_REJECTED", "A named display has unreadable ownership")
        // SurfaceDaemon selects by the same global name heuristic. Until its
        // target contract takes an ID, every named alternative is ambiguous.
        if (candidates.size != 1) return result("AMBIGUOUS", "More than one named projection target")
        val d = candidates.single()
        if (d.isPrivate || d.ownerUid != 1000 || d.ownerPackage != "com.xdja.containerservice")
            return result("OWNER_REJECTED", "Projection display is private or its OEM owner is unconfirmed")
        if (d.state != "ON") return result("STATE_REJECTED", "Projection display is not positively ON")
        if (d.width == 1280 && d.height == 480) return result("SMALL", "Observed 1280x480 projection display; size forcing refused")
        if (!ClusterDashboardProfile.permitsGeometry(d.width, d.height, false))
            return result("SIZE_REJECTED", "Observed projection geometry ${d.width}x${d.height}; 1920x720 required")
        return result("READY", "One current OEM-owned ON 1920x720 projection display")
    }

    fun fromJson(j: JSONObject): Evidence {
        val verdict = j.getString("verdict")
        require(verdict in setOf("UNAVAILABLE", "ABSENT", "AMBIGUOUS", "OWNER_REJECTED", "STATE_REJECTED", "SMALL", "SIZE_REJECTED", "READY"))
        val rows = j.getJSONArray("candidates"); require(rows.length() <= 32)
        val displays = (0 until rows.length()).map { index -> rows.getJSONObject(index).let { d ->
            ClusterDisplayInfo(d.getInt("id"), d.getString("name"), d.getInt("width"), d.getInt("height"), -1,
                d.getBoolean("private"), d.getInt("ownerUid"), if (d.isNull("ownerPackage")) null else d.getString("ownerPackage"), d.getString("state"))
        } }
        if (verdict == "READY") require(inspect(displays).ready)
        return Evidence(verdict, j.getString("reason").take(384), displays)
    }
}
