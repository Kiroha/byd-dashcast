package com.byd.dashcast.fission

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting

import com.byd.dashcast.util.AppLogger

import org.json.JSONArray

object LayoutPrefs {

    private const val PREFS_NAME = "dashcast_fission_layouts_v1"
    private const val KEY_LIST = "presets"
    private const val KEY_LIST_BACKUP = "presets_backup"
    private const val KEY_LIST_QUARANTINE = "presets_corrupt"
    private const val KEY_FAVORITE_ID = "favorite_layout_id"
    private val WRITE_LOCK = Any()

    enum class LoadStatus { OK, RECOVERED, CORRUPT, STORAGE_ERROR }
    enum class FavoriteWriteStatus { SAVED, MISSING, STORAGE_ERROR }

    class LoadResult(
        @JvmField val presets: List<LayoutPreset>,
        @JvmField val status: LoadStatus,
    )

    @Volatile private var testPrefs: SharedPreferences? = null

    @VisibleForTesting
    @JvmStatic
    fun setPrefsForTesting(prefs: SharedPreferences?) {
        testPrefs = prefs
    }

    @JvmStatic
    fun load(ctx: Context): List<LayoutPreset> = loadResult(ctx).presets

    @JvmStatic
    fun loadResult(ctx: Context): LoadResult = synchronized(WRITE_LOCK) {
        val prefs = try {
            prefs(ctx)
        } catch (error: Exception) {
            AppLogger.e("LayoutPrefs", "layout storage unavailable", error)
            return@synchronized LoadResult(emptyList(), LoadStatus.STORAGE_ERROR)
        }
        val primary = try {
            prefs.getString(KEY_LIST, "[]") ?: "[]"
        } catch (error: Exception) {
            AppLogger.e("LayoutPrefs", "layout storage value unreadable", error)
            return@synchronized LoadResult(emptyList(), LoadStatus.STORAGE_ERROR)
        }
        try {
            return@synchronized LoadResult(decode(primary), LoadStatus.OK)
        } catch (primaryError: Exception) {
            AppLogger.e("LayoutPrefs", "saved layouts are corrupt; trying backup", primaryError)
        }

        val backup = try { prefs.getString(KEY_LIST_BACKUP, null) } catch (_: Exception) { null }
        if (backup != null) {
            try {
                val recovered = decode(backup)
                val restored = prefs.edit()
                    .putString(KEY_LIST_QUARANTINE, primary)
                    .putString(KEY_LIST, backup)
                    .commit()
                if (!restored) AppLogger.w("LayoutPrefs", "recovered layout backup could not be restored")
                else AppLogger.i("LayoutPrefs", "recovered saved layouts from backup")
                return@synchronized LoadResult(recovered,
                    if (restored) LoadStatus.RECOVERED else LoadStatus.STORAGE_ERROR)
            } catch (backupError: Exception) {
                AppLogger.e("LayoutPrefs", "saved layout backup is also corrupt", backupError)
            }
        }

        val quarantined = try {
            prefs.edit().putString(KEY_LIST_QUARANTINE, primary).commit()
        } catch (_: Exception) {
            false
        }
        if (!quarantined) AppLogger.w("LayoutPrefs", "corrupt saved layouts could not be quarantined")
        LoadResult(emptyList(), LoadStatus.CORRUPT)
    }

    private fun decode(json: String): List<LayoutPreset> {
        val result = ArrayList<LayoutPreset>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            result.add(LayoutPreset.fromJson(arr.getJSONObject(i)))
        }
        return result
    }

    @JvmStatic
    fun save(ctx: Context, presets: List<LayoutPreset>): Boolean =
        saveInternal(ctx, presets, updateFavorite = false, favoriteId = null)

    /** Atomically stores presets and their selected favourite. */
    @JvmStatic
    fun saveState(ctx: Context, presets: List<LayoutPreset>, favoriteId: String?): Boolean =
        saveInternal(ctx, presets, updateFavorite = true, favoriteId = favoriteId)

    private fun saveInternal(
        ctx: Context,
        presets: List<LayoutPreset>,
        updateFavorite: Boolean,
        favoriteId: String?,
    ): Boolean {
        return synchronized(WRITE_LOCK) { try {
            val arr = JSONArray()
            for (p in presets) arr.put(p.toJson())
            val prefs = prefs(ctx)
            val editor = prefs.edit().putString(KEY_LIST, arr.toString())
            if (updateFavorite) editor.putString(KEY_FAVORITE_ID, favoriteId)
            val current = try { prefs.getString(KEY_LIST, "[]") ?: "[]" } catch (_: Exception) { null }
            if (current != null) {
                try {
                    decode(current)
                    editor.putString(KEY_LIST_BACKUP, current)
                } catch (_: Exception) {
                    editor.putString(KEY_LIST_QUARANTINE, current)
                }
            }
            editor.commit().also {
                if (!it) AppLogger.w("LayoutPrefs", "saving layouts was not committed")
            }
        } catch (error: Exception) {
            AppLogger.e("LayoutPrefs", "saving layouts failed", error)
            false
        } }
    }

    @JvmStatic
    fun getFavoriteId(ctx: Context): String? =
        try { prefs(ctx).getString(KEY_FAVORITE_ID, null) }
        catch (error: Exception) {
            AppLogger.e("LayoutPrefs", "reading favourite layout failed", error)
            null
        }

    @JvmStatic
    fun setFavoriteId(ctx: Context, id: String?): Boolean = synchronized(WRITE_LOCK) {
        writeFavoriteId(ctx, id)
    }

    /** Writes [id] only while that preset still exists in the persisted list. */
    @JvmStatic
    fun setFavoriteIdIfPresent(ctx: Context, id: String): Boolean =
        setFavoriteIdIfPresentResult(ctx, id) == FavoriteWriteStatus.SAVED

    @JvmStatic
    fun setFavoriteIdIfPresentResult(
        ctx: Context,
        id: String,
    ): FavoriteWriteStatus = synchronized(WRITE_LOCK) {
        val current = try {
            decode(prefs(ctx).getString(KEY_LIST, "[]") ?: "[]")
        } catch (error: Exception) {
            AppLogger.e("LayoutPrefs", "cannot select favourite from unreadable layouts", error)
            return@synchronized FavoriteWriteStatus.STORAGE_ERROR
        }
        if (current.none { it.id == id }) {
            AppLogger.w("LayoutPrefs", "favourite layout no longer exists")
            return@synchronized FavoriteWriteStatus.MISSING
        }
        if (writeFavoriteId(ctx, id)) FavoriteWriteStatus.SAVED
        else FavoriteWriteStatus.STORAGE_ERROR
    }

    /** Returns a valid favorite id and best-effort clears an orphaned stored id. */
    @JvmStatic
    fun getValidFavoriteId(ctx: Context, presets: List<LayoutPreset>): String? =
        synchronized(WRITE_LOCK) {
            val id = getFavoriteId(ctx) ?: return@synchronized null
            if (presets.any { it.id == id }) return@synchronized id
            AppLogger.w("LayoutPrefs", "clearing orphaned favourite layout id")
            writeFavoriteId(ctx, null)
            null
        }

    private fun writeFavoriteId(ctx: Context, id: String?): Boolean = try {
        prefs(ctx).edit().putString(KEY_FAVORITE_ID, id).commit().also {
            if (!it) AppLogger.w("LayoutPrefs", "saving favourite layout was not committed")
        }
    } catch (error: Exception) {
        AppLogger.e("LayoutPrefs", "saving favourite layout failed", error)
        false
    }

    /** Returns the favorite layout, or null if none set or the id no longer exists. */
    @JvmStatic
    fun getFavoriteLayout(ctx: Context): LayoutPreset? {
        val presets = load(ctx)
        val id = getValidFavoriteId(ctx, presets) ?: return null
        for (p in presets) {
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
        val presets = load(ctx)
        val favoriteId = getValidFavoriteId(ctx, presets)
        val selected = LayoutAutoStartPolicy.chooseLayout(favoriteId, presets)
        if (selected != null && selected.id != favoriteId) {
            if (setFavoriteIdIfPresent(ctx, selected.id)) {
                AppLogger.i("LayoutPrefs", "auto-start repaired sole usable layout as favourite: " +
                    selected.name + " (" + selected.id + ")")
            } else {
                AppLogger.w("LayoutPrefs", "auto-start could not persist repaired favourite layout")
            }
        }
        return selected
    }

    private fun prefs(ctx: Context): SharedPreferences =
        testPrefs ?: ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
