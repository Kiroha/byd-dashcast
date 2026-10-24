package com.byd.dashcast.data.prefs

enum class StartupAppsMode { NONE, LAST_SESSION, APP, LAYOUT }

/** One source of applications; the independent projection switch never chooses content. */
data class StartupAppsSelection(val mode: StartupAppsMode, val target: String? = null) {
    fun hasValidAppTarget(): Boolean = target != null && target.length <= 255 &&
        target.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
}
