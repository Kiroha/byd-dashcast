package com.byd.dashcast.report

import android.content.Context
import java.io.File

/** Durable ownership journal for a completed report between capture and terminal delivery. */
internal object BugWizardPendingDelivery {
    const val AWAITING_SCREENSHOT_CONSENT = "awaiting_screenshot_consent"
    const val BUNDLING = "bundling"
    const val DELIVERING = "delivering"

    data class Record(val path: String, val caption: String, val phase: String) {
        fun file(): File = File(path)
    }

    private const val PREFS = "dashcast_pending_bug_delivery"
    private const val KEY_PATH = "path"
    private const val KEY_CAPTION = "caption"
    private const val KEY_PHASE = "phase"
    private val phases = setOf(AWAITING_SCREENSHOT_CONSENT, BUNDLING, DELIVERING)

    fun save(context: Context, file: File, caption: String, phase: String): Boolean {
        if (!file.isFile || phase !in phases) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PATH, file.absolutePath)
            .putString(KEY_CAPTION, caption)
            .putString(KEY_PHASE, phase)
            .commit()
    }

    fun load(context: Context): Record? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_PATH, null) ?: return null
        val caption = prefs.getString(KEY_CAPTION, "").orEmpty()
        val phase = prefs.getString(KEY_PHASE, null) ?: return null
        if (path.isEmpty() || phase !in phases) return null
        return Record(path, caption, phase)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
}