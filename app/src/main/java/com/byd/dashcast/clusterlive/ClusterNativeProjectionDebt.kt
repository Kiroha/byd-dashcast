package com.byd.dashcast.clusterlive

import android.content.Context

/** Durable negative-only gate across app restarts; never authorizes or proves cluster state. */
internal object ClusterNativeProjectionDebt {
    private const val STORE = "native_cluster_operational_debt"
    private const val KEY = "nonce"
    @Volatile private var failedClear = false
    fun nonce(context: Context): String? = try {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(KEY, null)
    } catch (_: Exception) { "UNKNOWN" }
    fun blocked(context: Context): Boolean = failedClear || nonce(context) != null
    fun begin(context: Context, nonce: String) {
        require(nonce.matches(Regex("[0-9a-f]{32}")))
        check(!blocked(context)) { "Previous native-mode operational debt remains; use manual BYD return" }
        check(context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString(KEY, nonce).commit()) {
            "Cannot durably record native-mode debt; request refused before IPC"
        }
    }
    fun clear(context: Context, nonce: String) {
        val stored = nonce(context)
        check(stored == null || stored == nonce) { "Native-mode debt nonce differs; retaining projection block" }
        val cleared = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().remove(KEY).commit()
        failedClear = !cleared
        check(cleared) { "Could not clear operational debt; projection remains blocked" }
    }
}
