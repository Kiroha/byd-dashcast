package com.byd.dashcast.model

import android.graphics.drawable.Drawable
import java.util.Locale

// @JvmField keeps direct field access (app.isFavorite, info.shortcuts…)
// working from the remaining Java call sites during the Kotlin migration.
class AppInfo(
    @JvmField val packageName: String,
    @JvmField val appName: String,
    @JvmField val icon: Drawable?
) {
    @JvmField var isFavorite = false
    @JvmField var isAutoLaunch = false
    @JvmField var launchCount = 0

    @JvmField var category = CATEGORY_OTHER
    @JvmField var shortcuts: MutableList<AppShortcut> = ArrayList()

    init {
        // Auto-Categorization logic based on package name or app name
        val pkg = packageName.lowercase(Locale.ROOT)
        if (NAVIGATION_KEYWORDS.any { pkg.contains(it) }) {
            category = CATEGORY_NAVIGATION
        } else if (MEDIA_KEYWORDS.any { pkg.contains(it) }) {
            category = CATEGORY_MEDIA
        }
    }

    companion object {
        const val CATEGORY_NAVIGATION = 1
        const val CATEGORY_MEDIA = 2
        const val CATEGORY_OTHER = 3

        private val NAVIGATION_KEYWORDS = listOf(
            "maps", "waze", "tomtom", "sygic", "navigation", "here",
            "yandex.navi", "telenav", "radarbot", "coyote", "osmand"
        )
        private val MEDIA_KEYWORDS = listOf(
            "spotify", "music", "youtube", "podcast", "radio", "vlc",
            "audible", "media", "player", "sound"
        )
    }
}
