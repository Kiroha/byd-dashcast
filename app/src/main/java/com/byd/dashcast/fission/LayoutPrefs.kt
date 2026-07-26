package com.byd.dashcast.fission

import android.content.Context

import androidx.core.content.edit

import com.byd.dashcast.util.AppLogger

import org.json.JSONArray

object LayoutPrefs {

    private const val PREFS_NAME = "dashcast_fission_layouts_v1"
    private const val KEY_LIST = "presets"
    private const val KEY_FAVORITE_ID = "favorite_layout_id"

    @JvmStatic
    fun load(ctx: Context): List<LayoutPreset> {
        val result = ArrayList<LayoutPreset>()
        try {
            val json = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LIST, "[]")
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                result.add(LayoutPreset.fromJson(arr.getJSONObject(i)))
            }
        } catch (ignored: Exception) {
        }
        return result
    }

    @JvmStatic
    fun save(ctx: Context, presets: List<LayoutPreset>) {
        try {
            val arr = JSONArray()
            for (p in presets) arr.put(p.toJson())
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putString(KEY_LIST, arr.toString()) }
        } catch (ignored: Exception) {
        }
    }

    @JvmStatic
    fun getFavoriteId(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FAVORITE_ID, null)

    @JvmStatic
    fun setFavoriteId(ctx: Context, id: String?) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_FAVORITE_ID, id) }
    }

    /** Returns the favorite layout, or null if none set or the id no longer exists. */
    @JvmStatic
    fun getFavoriteLayout(ctx: Context): LayoutPreset? {
        val id = getFavoriteId(ctx) ?: return null
        for (p in load(ctx)) {
            if (id == p.id) return p
        }
        return null
    }

    /**
     * Resolves a launchable automatic layout. Existing users who saved exactly one layout with
     * bound apps but never tapped "Favourite" are repaired automatically; multiple candidates
     * remain explicit to avoid launching an arbitrary layout.
     */
    @JvmStatic
    fun getAutoStartLayout(ctx: Context): LayoutPreset? {
        val favoriteId = getFavoriteId(ctx)
        val selected = LayoutAutoStartPolicy.chooseLayout(favoriteId, load(ctx))
        if (selected != null && selected.id != favoriteId) {
            setFavoriteId(ctx, selected.id)
            AppLogger.i("LayoutPrefs", "auto-start repaired sole usable layout as favourite: " +
                selected.name + " (" + selected.id + ")")
        }
        return selected
    }
}
