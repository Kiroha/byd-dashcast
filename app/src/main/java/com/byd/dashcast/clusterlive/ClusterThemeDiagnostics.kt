package com.byd.dashcast.clusterlive

import android.content.Context
import android.annotation.SuppressLint
import androidx.core.content.edit
import com.byd.dashcast.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import java.security.MessageDigest

/** Historical evidence only. Never consulted by admission, recovery, movement or UI busy policy. */
@SuppressLint("StaticFieldLeak") // Only applicationContext is retained, never the Diag Activity.
internal object ClusterThemeDiagnostics {
    internal const val PREFERENCES = "cluster_theme_diagnostics"
    internal const val KEY = "history"
    internal const val MAX_BYTES = 48 * 1024
    private const val MAX_EVENTS = 24
    private val stringFields = mapOf("state" to 64, "detail" to 768, "error" to 384,
        "filename" to 160, "renderer" to 32, "rendererIdentity" to 256, "connectionToken" to 96,
        "operation" to 64, "kind" to 16, "rawSha256" to 64, "variant" to 64, "carrierSha256" to 64, "phase" to 64,
        "transport" to 32, "nativeIdentity" to 32, "rccTrace" to 8192, "retirement" to 512, "journalSchema" to 8, "carrierIdentity" to 128,
        "loadSend" to 128, "loadSelection" to 64, "loadAck2" to 64, "loadPixels" to 32,
        "oemQueryState" to 32, "oemQueryPhase" to 64, "oemQueryDetail" to 768,
        "oemQuerySelection" to 1536, "oemQueryNativeIdentity" to 16,
        "nativeModeState" to 32, "nativeModeNonce" to 32, "nativeModeDetail" to 768,
        "nativeModePhysicalPixels" to 16, "nativeModeQmlAck" to 16, "nativeModeDebounce" to 16, "nativeModeTransactionAttempted" to 16,
        "nativeModeTrace" to 6144, "nativeModeFullReceipt" to 1536, "nativeModeNormalReceipt" to 1536,
        "dashboardStage" to 32, "dashboardRound" to 32, "dashboardPixels" to 32)
    private val queryNumbers = listOf("oemQueryRegistration", "oemQueryNative", "oemQueryReceivedEpochMs",
        "nativeModeCommand", "nativeModeNative", "nativeModeReceivedEpochMs", "nativeModeQueuedElapsedMs", "nativeModeAdmittedElapsedMs",
        "dashboardHelperCommand")
    private val booleanFields = listOf("mutated", "uncertain", "iviRecoverySettled", "defaultQueried", "carrierRemoved",
        "markerObservedByUser", "themeObservedByUser", "oemObservedByUser", "rccDebt", "nativeModeDebt", "nativeModePending",
        "nativeModeUncertain", "nativeModeNormalSettled", "nativeModeObservedByUser", "retirementAvailable", "recoveryPending",
        "dashboardNativeDebt", "dashboardPending", "dashboardSelectionReady")
    private var store: Context? = null
    private var events = ArrayList<JSONObject>()
    private var cacheIssue = ""

    fun failure(error: Throwable): String {
        if (generateSequence(error) { it.cause }.take(16).any { it is JSONException })
            return "${error.javaClass.simpleName}: malformed status response (payload omitted)"
        return "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(384)
    }

    @Synchronized fun received(context: Context?, operation: String, connectionToken: String, text: String) {
        safely {
            val reply = JSONObject(text)
            val event = JSONObject().put("kind", "STATUS").put("operation", operation)
                .put("connectionToken", connectionToken)
            for (field in listOf("state", "detail", "error", "filename", "renderer", "variant", "carrierSha256", "phase", "transport", "nativeIdentity", "journalSchema", "carrierIdentity"))
                if (reply.has(field)) event.put(field, reply.get(field))
            if (reply.has("trace")) event.put("rccTrace", reply.getString("trace"))
            reply.optJSONObject("loadEvidence")?.let { evidence ->
                for (field in listOf("send", "selection", "ack2", "pixels"))
                    if (evidence.has(field)) event.put("load" + field.replaceFirstChar { it.uppercase() }, evidence.getString(field))
            }
            reply.optJSONObject("retirement")?.let { event.put("retirement", it.toString()) }
            if (reply.has("identity")) event.put("rendererIdentity", reply.get("identity"))
            if (reply.has("daemonUid")) event.put("daemonUid", reply.get("daemonUid"))
            for (field in booleanFields) if (reply.has(field)) event.put(field, reply.get(field))
            reply.optJSONObject("oemQuery")?.let { query ->
                for (field in listOf("state", "phase", "detail", "selection", "nativeIdentity", "registration", "native", "receivedEpochMs")) {
                    if (query.has(field) && !query.isNull(field)) event.put("oemQuery" + field.replaceFirstChar { it.uppercase() }, query.get(field))
                }
            }
            reply.optJSONObject("nativeMode")?.let { mode ->
                for (field in listOf("state", "nonce", "detail", "physicalPixels", "qmlAck", "debounce", "command", "native", "receivedEpochMs",
                    "debt", "pending", "uncertain", "normalSettled", "observedByUser", "transactionAttempted", "queuedElapsedMs", "admittedElapsedMs", "trace")) {
                    if (mode.has(field) && !mode.isNull(field)) event.put("nativeMode" + field.replaceFirstChar { it.uppercase() }, mode.get(field))
                }
                for (field in listOf("fullReceipt", "normalReceipt")) mode.optJSONObject(field)?.let {
                    event.put("nativeMode" + field.replaceFirstChar { it.uppercase() }, it.toString())
                }
            }
            reply.optJSONObject("dashboard")?.let { dashboard ->
                for (field in listOf("stage", "round", "pixels", "helperCommand", "nativeDebt", "pending", "selectionReady"))
                    if (dashboard.has(field) && !dashboard.isNull(field))
                        event.put("dashboard" + field.replaceFirstChar { it.uppercase() }, dashboard.get(field))
            }
            // Retain changes without storing Parcel/base64 payloads or unbounded helper output.
            val raw = reply.optString("raw").take(32768)
            if (raw.isNotEmpty()) event.put("rawSha256", MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
            record(context, event)
        }
    }

    @Synchronized fun local(context: Context?, operation: String, state: String, detail: String, error: String = "", connectionToken: String? = null) {
        safely { record(context, JSONObject().put("kind", "LOCAL").put("operation", operation)
            .put("state", state).put("detail", detail).put("error", error).apply {
                if (connectionToken != null) put("connectionToken", connectionToken)
            }) }
    }

    /** Only app-private cached data is read here. No pin/status/Binder/OEM read or reconnect. */
    @Synchronized fun snapshot(context: Context, now: Long = System.currentTimeMillis()): String {
        safely { load(context) }
        return buildString {
            append("Historical app-private cache; current physical cluster state=UNKNOWN; movement=UNKNOWN.\n")
            append("Dedicated RCC operational projection block nonce=").append(ClusterThemeProjectionDebt.nonce(context) ?: "NONE")
                .append(" blocked=").append(ClusterThemeProjectionDebt.blocked(context)).append("; negative-only gate, never native/render identity proof.\n")
            append("Dedicated native operational projection block nonce=").append(ClusterNativeProjectionDebt.nonce(context) ?: "NONE")
                .append(" blocked=").append(ClusterNativeProjectionDebt.blocked(context)).append("; negative-only admission gate, never mode/render proof.\n")
            append("Receipt time is not source freshness or render/recovery proof. No cluster IPC/OEM reads by this section.\n")
            append("connectionToken is an app-local Binder reference, NOT daemon-process identity; daemon process identity=NOT_RECORDED.\n")
            append("SharedPreferences writes are asynchronous/best effort; immediate process death may lose recent events.\n")
            if (cacheIssue.isNotEmpty()) append("cacheIssue=").append(cacheIssue).append('\n')
            if (events.isEmpty()) append("No cluster test state recorded; outcome=NOT_RECORDED, never IDLE/restored.\n")
            events.forEach { event ->
                val timestamp = event.getLong("receivedEpochMs")
                val age = if (now >= timestamp) (now - timestamp).toString() else "UNKNOWN(clock changed)"
                append("ageMs=").append(age).append(" historical ").append(event).append('\n')
            }
        }
    }

    private fun record(context: Context?, event: JSONObject) {
        if (context != null) load(context)
        val clean = validated(event, false)
        val key = stableKey(clean)
        val redundant = clean.getString("kind") == "LOCAL" || clean.getString("operation") == "status"
        val previous = events.lastOrNull {
            it.optString("kind") == clean.getString("kind") && (clean.getString("kind") != "LOCAL" ||
                it.optString("operation") == clean.getString("operation") && it.optString("connectionToken") == clean.optString("connectionToken"))
        }
        if (redundant && previous?.let(::stableKey) == key) return
        clean.put("receivedEpochMs", System.currentTimeMillis())
        events.add(clean)
        while (events.size > MAX_EVENTS || encode().toByteArray(Charsets.UTF_8).size > MAX_BYTES) events.removeAt(0)
        AppLogger.i("ClusterThemeDiag", clean.toString())
        store?.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)?.edit { putString(KEY, encode()) }
    }

    private fun load(context: Context) {
        val app = context.applicationContext
        if (store === app) return
        store = app
        events.clear(); cacheIssue = ""
        val text = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        try {
            require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "oversized" }
            val root = JSONObject(text)
            require(root.getInt("schema") == 1)
            val array = root.getJSONArray("events")
            require(array.length() <= MAX_EVENTS)
            val restored = ArrayList<JSONObject>()
            for (i in 0 until array.length()) restored.add(validated(array.getJSONObject(i), true))
            events = restored
        } catch (_: Throwable) { events.clear(); cacheIssue = "Unreadable/corrupt/oversized history; retained outcome UNKNOWN" }
    }

    private fun validated(source: JSONObject, persisted: Boolean): JSONObject {
        val result = JSONObject()
        for ((field, limit) in stringFields) if (source.has(field)) {
            require(source.get(field) is String)
            result.put(field, source.getString(field).replace(Regex("[\\p{Cntrl}]"), " ").take(limit))
        }
        require(result.optString("kind") in setOf("STATUS", "LOCAL"))
        require(result.has("state") && result.has("operation"))
        for (field in booleanFields) if (source.has(field)) {
            require(source.get(field) is Boolean); result.put(field, source.getBoolean(field))
        }
        for (field in queryNumbers) if (source.has(field)) {
            require(source.get(field) is Int || source.get(field) is Long)
            result.put(field, source.getLong(field))
        }
        if (source.has("daemonUid")) {
            require((source.get("daemonUid") is Int || source.get("daemonUid") is Long) && source.getLong("daemonUid") in 0..Int.MAX_VALUE)
            result.put("daemonUid", source.getInt("daemonUid"))
        }
        if (persisted) {
            require((source.get("receivedEpochMs") is Int || source.get("receivedEpochMs") is Long) && source.getLong("receivedEpochMs") > 0)
            result.put("receivedEpochMs", source.getLong("receivedEpochMs"))
        }
        return result
    }

    private fun stableKey(event: JSONObject): String = (stringFields.keys + booleanFields + queryNumbers + "daemonUid")
        .filter { it != "operation" || event.optString("kind") == "LOCAL" }
        .let { fields -> JSONArray(fields.map { field -> JSONArray(listOf(field, event.opt(field))) }).toString() }
    private fun encode() = JSONObject().put("schema", 1).put("events", JSONArray(events)).toString()
    private inline fun safely(action: () -> Unit) {
        try { action() } catch (error: Throwable) {
            cacheIssue = "Cache unavailable (${error.javaClass.simpleName}); evidence incomplete, outcome UNKNOWN"
        }
    }
}
