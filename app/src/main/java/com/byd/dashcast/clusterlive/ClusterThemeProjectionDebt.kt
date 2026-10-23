package com.byd.dashcast.clusterlive

import android.content.Context

/** Negative-only durable RCC gate. A cached receipt never authorizes an OEM operation. */
internal object ClusterThemeProjectionDebt {
    private const val STORE = "rcc_cluster_operational_debt"
    @Volatile private var failedClear = false
    fun nonce(context: Context): String? = try {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString("nonce", null)
    } catch (_: Exception) { "UNKNOWN" }
    fun blocked(context: Context) = failedClear || nonce(context) != null
    @Synchronized fun begin(context: Context, nonce: String) {
        require(nonce.matches(Regex("[0-9a-f]{32}")))
        check(!blocked(context)) { "Previous RCC recovery remains outstanding" }
        check(context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString("nonce", nonce).commit()) {
            "Cannot durably record RCC debt; no load submitted"
        }
    }
    @Synchronized fun clear(context: Context, nonce: String) {
        check(nonce(context) == null || nonce(context) == nonce) { "RCC debt nonce differs; projection remains blocked" }
        val saved = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().remove("nonce").commit()
        failedClear = !saved
        check(saved) { "Could not clear RCC debt; projection remains blocked" }
    }
}
