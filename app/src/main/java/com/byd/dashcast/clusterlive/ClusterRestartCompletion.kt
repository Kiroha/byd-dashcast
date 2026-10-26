package com.byd.dashcast.clusterlive

import org.json.JSONObject

/** Historical user observation in one boot, correlated to one dispatch intent; never current pixels. */
internal data class ClusterRestartCompletion(val round: String, val beforeBoot: String, val confirmedBoot: String) {
    init {
        require(round.matches(Regex("[0-9a-f]{32}")))
        val uuid = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        require(beforeBoot.matches(uuid) && confirmedBoot.matches(uuid) && beforeBoot != confirmedBoot)
    }
    fun json() = JSONObject().put("round", round).put("beforeBoot", beforeBoot).put("confirmedBoot", confirmedBoot)
    companion object {
        fun parse(value: JSONObject): ClusterRestartCompletion {
            for (key in listOf("round", "beforeBoot", "confirmedBoot")) require(value.get(key) is String)
            return ClusterRestartCompletion(value.getString("round"), value.getString("beforeBoot"), value.getString("confirmedBoot"))
        }
    }
}
