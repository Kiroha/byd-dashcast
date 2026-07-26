package com.byd.dashcast.ui.hotspot

internal data class HotspotStatsPayload(
    val serviceUp: Boolean,
    val clientsOutput: String
) {
    companion object {
        const val STATE_UP = "===TF_STATE=UP==="
        const val STATE_DOWN = "===TF_STATE=DOWN==="
        const val CLIENTS = "===CLIENTS==="

        fun parse(output: String?): HotspotStatsPayload? {
            if (output == null) return null
            val clientsAt = output.indexOf(CLIENTS)
            if (clientsAt < 0) return null
            val state = output.substring(0, clientsAt)
            if (!state.contains(STATE_UP) && !state.contains(STATE_DOWN)) return null
            return HotspotStatsPayload(
                serviceUp = state.contains(STATE_UP),
                clientsOutput = output.substring(clientsAt + CLIENTS.length).trimStart('\r', '\n')
            )
        }
    }
}