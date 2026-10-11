package com.byd.dashcast.media

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.Closeable

/** A separate preference file keeps music changes out of navigation preference observers. */
object MediaPrefs {
    private const val FILE = "dashcast_media_prefs"
    private const val ENABLED = "cluster_media_enabled"
    fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(ENABLED, false)
    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit { putBoolean(ENABLED, enabled) }
    }
    fun observe(context: Context, changed: () -> Unit): Closeable {
        val prefs = preferences(context)
        val observer = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ENABLED) changed()
        }
        prefs.registerOnSharedPreferenceChangeListener(observer)
        return Closeable { prefs.unregisterOnSharedPreferenceChangeListener(observer) }
    }
    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
