package com.byd.dashcast.report

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Durable ownership journal for a completed report between capture and terminal delivery. */
internal object BugWizardPendingDelivery {
    const val AWAITING_SCREENSHOT_CONSENT = "awaiting_screenshot_consent"
    const val BUNDLING = "bundling"
    const val DELIVERING = "delivering"

    data class Record(
        val path: String,
        val caption: String,
        val phase: String,
        val resumable: Boolean = true,
    ) {
        fun file(): File = File(path)
    }

    private const val PREFS = "dashcast_pending_bug_delivery"
    private const val KEY_PATH = "path"
    private const val KEY_CAPTION = "caption"
    private const val KEY_PHASE = "phase"
    private const val KEY_RESUMABLE = "resumable"
    private const val KEY_RECORDS = "records_v2"
    private val phases = setOf(AWAITING_SCREENSHOT_CONSENT, BUNDLING, DELIVERING)

    @Synchronized
    fun save(context: Context, file: File, caption: String, phase: String): Boolean {
        if (!file.isFile || phase !in phases) return false
        val prefs = prefs(context)
        val records = readAll(prefs)
        records.removeAll { it.path == file.absolutePath }
        records += Record(file.absolutePath, caption, phase)
        return writeAll(prefs, records)
    }

    @Synchronized
    fun protect(context: Context, file: File, caption: String): Boolean {
        if (!file.isFile) return false
        val prefs = prefs(context)
        val records = readAll(prefs)
        val existing = records.firstOrNull { it.path == file.absolutePath }
        if (existing?.resumable == true) return true
        records.removeAll { it.path == file.absolutePath }
        records += Record(
            file.absolutePath,
            caption,
            DELIVERING,
            resumable = false,
        )
        return writeAll(prefs, records)
    }

    @Synchronized
    fun load(context: Context): Record? =
        readAll(prefs(context)).lastOrNull { it.resumable }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }

    @Synchronized
    fun clear(context: Context, file: File) {
        val prefs = prefs(context)
        val records = readAll(prefs)
        if (!records.removeAll { it.path == file.absolutePath }) return
        writeAll(prefs, records)
    }

    @Synchronized
    fun protects(context: Context, file: File): Boolean =
        readAll(prefs(context)).any { it.path == file.absolutePath }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readAll(prefs: SharedPreferences): MutableList<Record> {
        val encoded = prefs.getString(KEY_RECORDS, null)
        if (!encoded.isNullOrEmpty()) {
            try {
                val array = JSONArray(encoded)
                val records = ArrayList<Record>(array.length())
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val path = item.optString(KEY_PATH)
                    val caption = item.optString(KEY_CAPTION)
                    val phase = item.optString(KEY_PHASE)
                    if (path.isNotEmpty() && phase in phases) {
                        records += Record(
                            path,
                            caption,
                            phase,
                            item.optBoolean(KEY_RESUMABLE, true),
                        )
                    }
                }
                return records
            } catch (_: Throwable) {
                // Fall through to the v1 singleton keys, then rewrite on the next mutation.
            }
        }
        val path = prefs.getString(KEY_PATH, null).orEmpty()
        val phase = prefs.getString(KEY_PHASE, null).orEmpty()
        if (path.isEmpty() || phase !in phases) return ArrayList()
        return arrayListOf(Record(
            path,
            prefs.getString(KEY_CAPTION, "").orEmpty(),
            phase,
        ))
    }

    private fun writeAll(prefs: SharedPreferences, records: List<Record>): Boolean {
        val array = JSONArray()
        for (record in records) {
            array.put(JSONObject()
                .put(KEY_PATH, record.path)
                .put(KEY_CAPTION, record.caption)
                .put(KEY_PHASE, record.phase)
                .put(KEY_RESUMABLE, record.resumable))
        }
        return prefs.edit()
            .putString(KEY_RECORDS, array.toString())
            .remove(KEY_PATH)
            .remove(KEY_CAPTION)
            .remove(KEY_PHASE)
            .commit()
    }
}