package com.byd.dashcast.clusterlive

import android.content.Context

/** Persist before ADB OPEN. Even a timeout or process death leaves this explicit no-replay debt. */
internal class ClusterRestartIntent(context: Context, private val boot: () -> String = ClusterBootIdentity::read) {
    private val prefs = context.getSharedPreferences("cluster_restart_intent", Context.MODE_PRIVATE)
    private fun bootValue(value: String?): String = checkNotNull(value) { "Missing restart boot identity" }.also {
        check(it.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))) { "Invalid restart boot identity" }
    }
    @Synchronized fun record(filename: String, round: String) {
        require(ClusterThemePolicy.ownedName(filename) && round.matches(Regex("[0-9a-f]{32}")))
        check(prefs.getString("filename", null) == null) { "Previous restart outcome remains unknown; verify the BYD return first" }
        val currentBoot = bootValue(boot())
        check(prefs.edit().putString("filename", filename).putString("round", round).putString("boot", currentBoot).commit()) { "Cannot persist restart intent" }
    }
    fun pending(): Boolean = prefs.contains("filename")
    @Synchronized fun observedRestored(filename: String, historical: ClusterRestartCompletion? = null) {
        if (prefs.getString("filename", null) == filename) {
            val before = bootValue(prefs.getString("boot", null)); val current = bootValue(boot())
            check(before != current) { "Boot identity unchanged; restart completion remains unknown" }
            if (historical != null) check(historical.beforeBoot == before && historical.confirmedBoot == current &&
                historical.round == prefs.getString("round", null)) { "Historical recovery confirmation belongs to another restart" }
            check(prefs.edit().clear().commit()) { "Cannot close restart intent" }
        }
    }
}
